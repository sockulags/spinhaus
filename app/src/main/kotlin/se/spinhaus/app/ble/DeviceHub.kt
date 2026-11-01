package se.spinhaus.app.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import se.spinhaus.app.data.DeviceRepository
import se.spinhaus.core.ble.RideButton

/**
 * Owns the connections to the bike: one trainer, any number of controllers (shared),
 * and the active profile's heart-rate sensor.
 */
class DeviceHub(
    private val context: Context,
    private val scope: CoroutineScope,
    private val devices: DeviceRepository,
    val log: BleLog,
) {
    val scanner = BleScanner(context, log)

    private val _trainer = MutableStateFlow<TrainerConnection?>(null)
    val trainer: StateFlow<TrainerConnection?> = _trainer
    private val _controllers = MutableStateFlow<List<ControllerConnection>>(emptyList())
    val controllers: StateFlow<List<ControllerConnection>> = _controllers
    private val _hr = MutableStateFlow<HeartRateConnection?>(null)
    val heartRate: StateFlow<HeartRateConnection?> = _hr

    /** Merged raw button sets from all controllers, tagged by address. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val buttons: Flow<Pair<String, Set<RideButton>>> = _controllers.flatMapLatest { list ->
        if (list.isEmpty()) emptyFlow() else merge(*list.map { c -> mapButtons(c) }.toTypedArray())
    }

    private fun mapButtons(c: ControllerConnection): Flow<Pair<String, Set<RideButton>>> =
        c.buttons.map { c.address to it }

    private var started = false

    private val _bluetoothOn = MutableStateFlow(scanner.bluetoothEnabled)
    /** Live adapter state for the UI. */
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn

    private val adapterReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    log.log("hub", "Bluetooth turned off")
                    _bluetoothOn.value = false
                    scanner.onAdapterOff()
                    _trainer.value?.dropLink()
                    _controllers.value.forEach { it.dropLink() }
                    _hr.value?.dropLink()
                }
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    log.log("hub", "Bluetooth turned on")
                    _bluetoothOn.value = true
                    reconnectAll()
                }
            }
        }
    }

    init {
        androidx.core.content.ContextCompat.registerReceiver(
            context, adapterReceiver,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Connects remembered shared devices (trainer + controllers). */
    fun startShared() {
        if (started) return
        started = true
        scope.launch {
            for (d in devices.all()) {
                when (d.type) {
                    "TRAINER" -> connectTrainer(d.address, d.name, remember = false)
                    "CONTROLLER" -> connectController(d.address, d.name, remember = false)
                }
            }
        }
    }

    fun connectTrainer(address: String, name: String?, remember: Boolean = true) {
        val dev = scanner.remoteDevice(address) ?: return
        _trainer.value?.let { if (it.address == address) return; it.stop() }
        val c = TrainerConnection(context, dev, log, scope)
        _trainer.value = c
        c.start()
        if (remember) scope.launch {
            devices.forgetType("TRAINER")
            devices.remember(address, name ?: address, "TRAINER")
        }
    }

    fun forgetTrainer() {
        _trainer.value?.stop()
        _trainer.value = null
        scope.launch { devices.forgetType("TRAINER") }
    }

    fun connectController(address: String, name: String?, remember: Boolean = true) {
        if (_controllers.value.any { it.address == address }) return
        val dev = scanner.remoteDevice(address) ?: return
        val c = ControllerConnection(context, dev, log, scope)
        _controllers.value = _controllers.value + c
        c.start()
        if (remember) scope.launch { devices.remember(address, name ?: address, "CONTROLLER") }
    }

    fun forgetController(address: String) {
        _controllers.value.firstOrNull { it.address == address }?.stop()
        _controllers.value = _controllers.value.filter { it.address != address }
        scope.launch { devices.forget(address) }
    }

    /** Switches the heart-rate sensor (per profile). Null disconnects. */
    fun useHeartRate(address: String?) {
        val cur = _hr.value
        if (cur?.address == address) {
            cur?.start()
            return
        }
        cur?.stop()
        _hr.value = null
        if (address == null) return
        val dev = scanner.remoteDevice(address) ?: return
        val c = HeartRateConnection(context, dev, log, scope)
        _hr.value = c
        c.start()
    }

    fun vibrateControllers() = _controllers.value.forEach { it.vibrate() }

    fun reconnectAll() {
        _trainer.value?.let { it.stop(); it.start() }
        _controllers.value.forEach { it.stop(); it.start() }
        _hr.value?.let { it.stop(); it.start() }
    }
}
