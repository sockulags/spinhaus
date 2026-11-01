package se.spinhaus.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import se.spinhaus.core.ble.GattUuids
import se.spinhaus.core.ble.ZwiftRideProtocol

enum class DeviceKind { TRAINER, CONTROLLER, HR, OTHER }

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val kind: DeviceKind,
    val serviceUuids: List<String>,
    val lastSeenMs: Long,
    val detail: String? = null,
)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context, private val log: BleLog) {
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val _results = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())
    val results: StateFlow<Map<String, ScannedDevice>> = _results
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) {
            log.log("scan", "scan failed $errorCode")
            _scanning.value = false
        }
    }

    private fun handle(r: ScanResult) {
        val record = r.scanRecord
        val uuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
        val manu = HashMap<Int, ByteArray>()
        record?.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) manu[sa.keyAt(i)] = sa.valueAt(i) }
        val name = record?.deviceName ?: runCatching { r.device.name }.getOrNull()
        val kind = classify(name, uuids, manu)
        val detail = manu[ZwiftRideProtocol.MANUFACTURER_ID]?.takeIf { it.isNotEmpty() }?.let { ZwiftRideProtocol.deviceTypeOf(it)?.name }
        val prev = _results.value[r.device.address]
        if (prev == null) log.log("scan", "found ${r.device.address} '$name' kind=$kind uuids=$uuids zwift=$detail")
        _results.value = _results.value + (r.device.address to ScannedDevice(
            r.device.address, name ?: prev?.name, r.rssi, if (kind == DeviceKind.OTHER && prev != null) prev.kind else kind,
            uuids.ifEmpty { prev?.serviceUuids ?: emptyList() }, System.currentTimeMillis(), detail ?: prev?.detail,
        ))
    }

    fun classify(name: String?, uuids: List<String>, manu: Map<Int, ByteArray>): DeviceKind = when {
        ZwiftRideProtocol.isZwiftController(name, manu, uuids) -> DeviceKind.CONTROLLER
        uuids.contains(GattUuids.FTMS_SERVICE) || uuids.contains(GattUuids.CPS_SERVICE) -> DeviceKind.TRAINER
        name?.contains("KICKR", true) == true || name?.contains("Zwift Hub", true) == true -> DeviceKind.TRAINER
        uuids.contains(GattUuids.HR_SERVICE) -> DeviceKind.HR
        name != null && (name.contains("Polar", true) || name.contains("HRM", true) || name.contains("Garmin", true)) -> DeviceKind.HR
        else -> DeviceKind.OTHER
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _results.value = emptyMap()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
            _scanning.value = true
            log.log("scan", "scan started")
        } catch (e: Exception) {
            log.log("scan", "start failed ${e.message}")
        }
    }

    fun stop() {
        if (!_scanning.value) return
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        _scanning.value = false
        log.log("scan", "scan stopped")
    }

    fun remoteDevice(address: String) = adapter?.getRemoteDevice(address)
}
