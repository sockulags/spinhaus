package se.spinhaus.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.spinhaus.core.ble.CrankCadenceCalculator
import se.spinhaus.core.ble.CyclingPowerParser
import se.spinhaus.core.ble.FtmsControl
import se.spinhaus.core.ble.FtmsParser
import se.spinhaus.core.ble.GattUuids
import se.spinhaus.core.ble.HeartRateParser
import se.spinhaus.core.ble.RideButton
import se.spinhaus.core.ble.ZwiftRideProtocol
import se.spinhaus.core.ride.TrainerCommand
import java.util.UUID

enum class DeviceStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING, LOCKED, UNSUPPORTED }

/** Base: keeps a device connected and reconnects automatically while [enabled]. */
@SuppressLint("MissingPermission")
abstract class DeviceConnection(
    protected val context: Context,
    val device: BluetoothDevice,
    protected val log: BleLog,
    protected val scope: CoroutineScope,
    val tag: String,
) {
    protected val client = GattClient(context, device, log, tag)
    private var job: Job? = null
    val address: String get() = device.address
    val name: String get() = runCatching { device.name }.getOrNull() ?: device.address

    private val _status = MutableStateFlow(DeviceStatus.IDLE)
    val status: StateFlow<DeviceStatus> = _status
    protected fun setStatus(s: DeviceStatus) { _status.value = s }

    var batteryPercent = MutableStateFlow<Int?>(null)
        protected set
    var firmware = MutableStateFlow<String?>(null)
        protected set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                if (_status.value != DeviceStatus.LOCKED) {
                    setStatus(if (attempt == 0) DeviceStatus.CONNECTING else DeviceStatus.RECONNECTING)
                }
                val ok = client.connect()
                if (ok) {
                    // Collect before setup so responses sent during setup (control point, RideOn) are not lost.
                    val notifJob = launch { client.notifications.collect { onNotification(it.characteristic, it.value) } }
                    val ready = try { onConnected() } catch (e: Exception) {
                        log.log(tag, "setup failed: ${e.message}"); false
                    }
                    if (ready) {
                        attempt = 0
                        setStatus(DeviceStatus.CONNECTED)
                        // wait until link drops
                        client.state.first { it == LinkState.DISCONNECTED }
                        notifJob.cancel()
                        onDisconnected()
                        log.log(tag, "disconnected")
                    } else if (_status.value == DeviceStatus.LOCKED || _status.value == DeviceStatus.UNSUPPORTED) {
                        notifJob.cancel()
                        client.close()
                        delay(20000)
                    } else {
                        notifJob.cancel()
                        client.close()
                    }
                }
                attempt++
                delay(if (attempt < 5) 2000L else 5000L)
            }
        }
    }

    /** Drops the link (e.g. Bluetooth turned off); the supervisor loop reconnects later. */
    fun dropLink() = client.close()

    fun stop() {
        job?.cancel()
        job = null
        client.close()
        onDisconnected()
        setStatus(DeviceStatus.IDLE)
    }

    /** Called after services are discovered. Return true when ready. */
    protected abstract suspend fun onConnected(): Boolean
    protected abstract fun onNotification(uuid: UUID, value: ByteArray)
    protected open fun onDisconnected() {}

    protected suspend fun readDeviceInfo() {
        client.findCharacteristic(GattUuids.DEVICE_INFO_SERVICE, GattUuids.FIRMWARE_REVISION)?.let { c ->
            client.read(c)?.let { firmware.value = String(it).trim() }
        }
        client.findCharacteristic(GattUuids.BATTERY_SERVICE, GattUuids.BATTERY_LEVEL)?.let { c ->
            client.read(c)?.firstOrNull()?.let { batteryPercent.value = it.toInt() and 0xFF }
            client.subscribe(c)
        }
    }

    protected fun handleBattery(uuid: UUID, value: ByteArray): Boolean {
        if (uuid == UUID.fromString(GattUuids.BATTERY_LEVEL) && value.isNotEmpty()) {
            batteryPercent.value = value[0].toInt() and 0xFF
            return true
        }
        return false
    }
}

data class TrainerData(
    val powerW: Int? = null,
    val cadenceRpm: Double? = null,
    val speedKmh: Double? = null,
    val atMs: Long = 0,
)

data class TrainerCapabilities(
    val ftms: Boolean = false,
    val power: Boolean = true,
    val erg: Boolean = false,
    val simulation: Boolean = false,
    val resistance: Boolean = false,
    val powerRange: IntRange = 0..2000,
)

/** Smart trainer via FTMS (with Cycling Power as data-only fallback). */
@SuppressLint("MissingPermission")
class TrainerConnection(context: Context, device: BluetoothDevice, log: BleLog, scope: CoroutineScope) :
    DeviceConnection(context, device, log, scope, "trainer") {

    private val _data = MutableStateFlow(TrainerData())
    val data: StateFlow<TrainerData> = _data
    private val _caps = MutableStateFlow(TrainerCapabilities())
    val capabilities: StateFlow<TrainerCapabilities> = _caps
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult

    private var controlPoint: BluetoothGattCharacteristic? = null
    @Volatile private var hasControl = false
    private var controlResponse: kotlinx.coroutines.CompletableDeferred<Int>? = null
    private var lastControlAttemptMs = 0L
    private val _controlledElsewhere = MutableStateFlow(false)
    /** True when the trainer refused control (another app such as Zwift is connected to it). */
    val controlledElsewhere: StateFlow<Boolean> = _controlledElsewhere
    private val crank = CrankCadenceCalculator()
    private var usingFtmsData = false

    override suspend fun onConnected(): Boolean {
        readDeviceInfo()
        hasControl = false
        if (client.hasService(GattUuids.FTMS_SERVICE)) {
            val features = client.findCharacteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_FEATURE)?.let { client.read(it) }
                ?.let { FtmsParser.parseFeatures(it) }
            val range = client.findCharacteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_SUPPORTED_POWER_RANGE)?.let { client.read(it) }
                ?.let { FtmsParser.parsePowerRange(it) }
            _caps.value = TrainerCapabilities(
                ftms = true, power = true,
                erg = features?.supportsPowerTarget ?: true,
                simulation = features?.supportsSimulation ?: true,
                resistance = features?.supportsResistanceTarget ?: false,
                powerRange = range?.let { it.minW.coerceAtLeast(0)..it.maxW.coerceAtLeast(100) } ?: 0..2000,
            )
            log.log(tag, "features=$features range=$range")
            client.findCharacteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_INDOOR_BIKE_DATA)?.let {
                usingFtmsData = client.subscribe(it)
            }
            client.findCharacteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_MACHINE_STATUS)?.let { client.subscribe(it) }
            controlPoint = client.findCharacteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_CONTROL_POINT)
            controlPoint?.let { cp ->
                if (!client.enableNotifications(cp, indicate = true)) {
                    log.log(tag, "could not enable control point indications")
                    return false
                }
                requestControl()
            }
        }
        if (!usingFtmsData && client.hasService(GattUuids.CPS_SERVICE)) {
            client.findCharacteristic(GattUuids.CPS_SERVICE, GattUuids.CPS_MEASUREMENT)?.let { client.subscribe(it) }
            if (!client.hasService(GattUuids.FTMS_SERVICE)) _caps.value = TrainerCapabilities(ftms = false)
        }
        if (!client.hasService(GattUuids.FTMS_SERVICE) && !client.hasService(GattUuids.CPS_SERVICE)) {
            setStatus(DeviceStatus.UNSUPPORTED)
            return false
        }
        return true
    }

    /** Requests control and waits for the trainer's answer. */
    private suspend fun requestControl(): Boolean {
        val cp = controlPoint ?: return false
        lastControlAttemptMs = System.currentTimeMillis()
        val d = kotlinx.coroutines.CompletableDeferred<Int>()
        controlResponse = d
        if (!client.write(cp, FtmsControl.requestControl())) return false
        val result = kotlinx.coroutines.withTimeoutOrNull(3000) { d.await() }
        controlResponse = null
        when (result) {
            FtmsControl.RESULT_SUCCESS, null -> {
                // null: no indication received – assume OK, some trainers don't answer.
                hasControl = true
                _controlledElsewhere.value = false
                client.write(cp, FtmsControl.start())
            }
            FtmsControl.RESULT_CONTROL_NOT_PERMITTED -> {
                hasControl = false
                _controlledElsewhere.value = true
                log.log(tag, "control refused – another app controls the trainer")
            }
            else -> hasControl = false
        }
        return hasControl
    }

    private suspend fun ensureControl(): Boolean {
        if (hasControl) return true
        if (System.currentTimeMillis() - lastControlAttemptMs < 5000) return false
        return requestControl()
    }

    override fun onDisconnected() {
        hasControl = false
        _controlledElsewhere.value = false
        usingFtmsData = false
        _data.value = TrainerData()
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        val now = System.currentTimeMillis()
        when (uuid.toString()) {
            GattUuids.FTMS_INDOOR_BIKE_DATA -> {
                val d = FtmsParser.parseIndoorBikeData(value) ?: return
                // Some trainers split data over two packets (More Data flag); merge.
                val prev = _data.value
                _data.value = TrainerData(
                    powerW = d.powerW ?: prev.powerW,
                    cadenceRpm = d.cadenceRpm ?: prev.cadenceRpm,
                    speedKmh = d.speedKmh ?: prev.speedKmh,
                    atMs = now,
                )
            }
            GattUuids.CPS_MEASUREMENT -> if (!usingFtmsData) {
                val m = CyclingPowerParser.parse(value) ?: return
                val cad = if (m.crankRevolutions != null && m.lastCrankEventTime1024 != null) {
                    crank.update(m.crankRevolutions!!, m.lastCrankEventTime1024!!, now)
                } else _data.value.cadenceRpm
                _data.value = TrainerData(powerW = m.powerW, cadenceRpm = cad, speedKmh = null, atMs = now)
            }
            GattUuids.FTMS_CONTROL_POINT -> {
                val r = FtmsParser.parseControlPointResponse(value) ?: return
                _lastResult.value = "op=0x%02x result=%d".format(r.requestOpCode, r.result)
                if (r.requestOpCode == FtmsControl.OP_REQUEST_CONTROL) {
                    controlResponse?.complete(r.result)
                } else if (r.result == FtmsControl.RESULT_CONTROL_NOT_PERMITTED) {
                    // Lost control (e.g. trainer reset); re-request on the next send, with back-off.
                    hasControl = false
                }
            }
            else -> handleBattery(uuid, value)
        }
    }

    suspend fun send(cmd: TrainerCommand): Boolean {
        val cp = controlPoint ?: return false
        if (status.value != DeviceStatus.CONNECTED) return false
        if (!ensureControl()) return false
        val bytes = when (cmd) {
            is TrainerCommand.TargetPower -> FtmsControl.setTargetPower(cmd.watts)
            is TrainerCommand.Simulation -> FtmsControl.setSimulation(cmd.params.gradePercent, cmd.params.crr, cmd.params.cw, cmd.params.windMs)
        }
        return client.write(cp, bytes)
    }

    suspend fun sendRaw(bytes: ByteArray): Boolean {
        val cp = controlPoint ?: return false
        if (!hasControl) requestControl()
        if (!hasControl) return false
        return client.write(cp, bytes)
    }
}

@SuppressLint("MissingPermission")
class HeartRateConnection(context: Context, device: BluetoothDevice, log: BleLog, scope: CoroutineScope) :
    DeviceConnection(context, device, log, scope, "hr") {

    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm
    var lastAtMs = 0L
        private set

    override suspend fun onConnected(): Boolean {
        val c = client.findCharacteristic(GattUuids.HR_SERVICE, GattUuids.HR_MEASUREMENT)
        if (c == null) {
            setStatus(DeviceStatus.UNSUPPORTED)
            return false
        }
        readDeviceInfo()
        return client.subscribe(c)
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        if (uuid.toString() == GattUuids.HR_MEASUREMENT) {
            HeartRateParser.parse(value)?.let {
                _bpm.value = it.bpm
                lastAtMs = System.currentTimeMillis()
            }
        } else handleBattery(uuid, value)
    }

    override fun onDisconnected() {
        _bpm.value = null
    }
}

/** Zwift Ride / Play / Click controller. */
@SuppressLint("MissingPermission")
class ControllerConnection(context: Context, device: BluetoothDevice, log: BleLog, scope: CoroutineScope) :
    DeviceConnection(context, device, log, scope, "controller") {

    private val _buttons = MutableSharedFlow<Set<RideButton>>(extraBufferCapacity = 64)
    /** Raw "currently pressed" sets (repeated by the device). */
    val buttons: SharedFlow<Set<RideButton>> = _buttons
    private val _lastPressed = MutableStateFlow<Set<RideButton>>(emptySet())
    val lastPressed: StateFlow<Set<RideButton>> = _lastPressed
    private var syncRx: BluetoothGattCharacteristic? = null

    override suspend fun onConnected(): Boolean {
        readDeviceInfo()
        val service = client.services.firstOrNull { it.uuid.toString().equals(GattUuids.ZWIFT_RIDE_SERVICE, true) }
            ?: client.services.firstOrNull { it.uuid.toString().equals(GattUuids.ZWIFT_CUSTOM_SERVICE, true) }
        if (service == null) {
            // Ride V2 / Click V2: Zwift hides the service until the controller is unlocked in Zwift.
            log.log(tag, "Zwift service hidden – controller is locked (firmware ${firmware.value})")
            setStatus(DeviceStatus.LOCKED)
            return false
        }
        val chars = service.characteristics
        fun byUuid(u: String) = chars.firstOrNull { it.uuid.toString().equals(u, true) }
        val async = byUuid(GattUuids.ZWIFT_ASYNC)
            ?: chars.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
        val syncTx = byUuid(GattUuids.ZWIFT_SYNC_TX)
            ?: chars.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 }
        syncRx = byUuid(GattUuids.ZWIFT_SYNC_RX)
            ?: chars.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 }
        if (async == null || syncRx == null) {
            setStatus(DeviceStatus.UNSUPPORTED)
            return false
        }
        client.subscribe(async)
        syncTx?.let { client.subscribe(it) }
        val rx = syncRx!!
        val noResponse = rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        client.write(rx, ZwiftRideProtocol.RIDE_ON, withResponse = !noResponse)
        return true
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        if (handleBattery(uuid, value)) return
        when (val m = ZwiftRideProtocol.decode(value)) {
            is ZwiftRideProtocol.Message.Buttons -> {
                _buttons.tryEmit(m.pressed)
                if (m.pressed.isNotEmpty()) _lastPressed.value = m.pressed
            }
            is ZwiftRideProtocol.Message.Battery -> batteryPercent.value = m.percent
            else -> {}
        }
    }

    override fun onDisconnected() {
        _buttons.tryEmit(emptySet())
    }

    fun vibrate() {
        val rx = syncRx ?: return
        scope.launch { client.write(rx, ZwiftRideProtocol.vibrateCommand(), withResponse = false) }
    }
}
