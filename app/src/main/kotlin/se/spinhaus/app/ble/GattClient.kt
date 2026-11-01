package se.spinhaus.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import se.spinhaus.core.ble.GattUuids
import se.spinhaus.core.util.toHex
import java.util.UUID

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * Coroutine wrapper around [BluetoothGatt] that serialises all GATT operations
 * (Android only allows one outstanding operation at a time).
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val log: BleLog,
    private val tag: String,
) {
    @Volatile private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()

    /** The single outstanding GATT operation; callbacks only complete it if the key matches. */
    private class PendingOp(val key: String) {
        val result = CompletableDeferred<Any?>()
    }
    @Volatile private var pending: PendingOp? = null

    private fun complete(key: String, value: Any?) {
        val p = pending ?: return
        if (p.key == key) p.result.complete(value)
        else log.log(tag, "ignored late callback $key (waiting for ${p.key})")
    }
    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var discoverDeferred: CompletableDeferred<Boolean>? = null

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notifications: SharedFlow<Notification> = _notifications

    val services: List<BluetoothGattService> get() = gatt?.services ?: emptyList()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log.log(tag, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = LinkState.CONNECTED
                connectDeferred?.complete(true)
            } else {
                _state.value = LinkState.DISCONNECTED
                connectDeferred?.complete(false)
                discoverDeferred?.complete(false)
                pending?.result?.complete(null)
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log.log(tag, "services discovered status=$status: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
            discoverDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            emit(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                emit(c.uuid, c.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            complete("r:${c.uuid}", if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                complete("r:${c.uuid}", if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            complete("w:${c.uuid}", status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            complete("d:${d.characteristic.uuid}", status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log.log(tag, "mtu=$mtu status=$status")
            complete("mtu", status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun emit(uuid: UUID, value: ByteArray) {
        log.log(tag, "<- ${short(uuid)} ${value.toHex()}")
        _notifications.tryEmit(Notification(uuid, value))
    }

    private fun short(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s.substring(0, 8)
    }

    suspend fun connect(timeoutMs: Long = 15000): Boolean {
        close()
        _state.value = LinkState.CONNECTING
        val d = CompletableDeferred<Boolean>()
        connectDeferred = d
        log.log(tag, "connecting to ${device.address}")
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log.log(tag, "no Bluetooth permission: ${e.message}")
            null
        }
        if (gatt == null) {
            _state.value = LinkState.DISCONNECTED
            return false
        }
        val ok = withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        if (!ok) {
            log.log(tag, "connect failed/timeout")
            close()
            return false
        }
        val dd = CompletableDeferred<Boolean>()
        discoverDeferred = dd
        val g = gatt ?: return false
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!(try { g.discoverServices() } catch (e: SecurityException) { false })) {
            log.log(tag, "discoverServices() returned false")
            close(); return false
        }
        val discovered = withTimeoutOrNull(15000) { dd.await() } ?: false
        if (!discovered) {
            close(); return false
        }
        return true
    }

    fun findCharacteristic(service: String, characteristic: String): BluetoothGattCharacteristic? =
        gatt?.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))

    fun hasService(service: String): Boolean = gatt?.getService(UUID.fromString(service)) != null

    private suspend fun op(key: String, timeoutMs: Long = 5000, block: (BluetoothGatt) -> Boolean): Any? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        val p = PendingOp(key)
        pending = p
        val started = try { block(g) } catch (e: Exception) {
            log.log(tag, "op $key exception ${e.message}"); false
        }
        if (!started) {
            pending = null
            return@withLock null
        }
        val r = withTimeoutOrNull(timeoutMs) { p.result.await() }
        if (r == null) log.log(tag, "op $key timed out")
        pending = null
        r
    }

    suspend fun enableNotifications(c: BluetoothGattCharacteristic, indicate: Boolean = false): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val desc = c.getDescriptor(UUID.fromString(GattUuids.CCCD)) ?: return true
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        log.log(tag, "subscribe ${short(c.uuid)} indicate=$indicate")
        return (op("d:${c.uuid}") { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(desc, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(desc)
            }
        } as? Boolean) ?: false
    }

    /** Subscribes with notify or indicate depending on the characteristic's properties. */
    suspend fun subscribe(c: BluetoothGattCharacteristic): Boolean {
        val props = c.properties
        val indicate = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 && props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        return enableNotifications(c, indicate)
    }

    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean = true): Boolean {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        log.log(tag, "-> ${short(c.uuid)} ${value.toHex()}")
        return (op("w:${c.uuid}") { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } as? Boolean) ?: false
    }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray? = op("r:${c.uuid}") { g ->
        @Suppress("DEPRECATION")
        g.readCharacteristic(c)
    } as? ByteArray

    suspend fun requestMtu(mtu: Int): Boolean = (op("mtu") { g -> g.requestMtu(mtu) } as? Boolean) ?: false

    fun close() {
        val g = gatt
        gatt = null
        if (g != null) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        pending?.result?.complete(null)
        _state.value = LinkState.DISCONNECTED
    }
}
