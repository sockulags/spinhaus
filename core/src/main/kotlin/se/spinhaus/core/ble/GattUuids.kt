package se.spinhaus.core.ble

/** Bluetooth GATT UUIDs used by Spinhaus (lower-case, full 128-bit form). */
object GattUuids {
    private fun sig(short: String) = "0000$short-0000-1000-8000-00805f9b34fb"

    // Fitness Machine Service (FTMS)
    val FTMS_SERVICE = sig("1826")
    val FTMS_FEATURE = sig("2acc")
    val FTMS_INDOOR_BIKE_DATA = sig("2ad2")
    val FTMS_SUPPORTED_RESISTANCE_RANGE = sig("2ad6")
    val FTMS_SUPPORTED_POWER_RANGE = sig("2ad8")
    val FTMS_CONTROL_POINT = sig("2ad9")
    val FTMS_MACHINE_STATUS = sig("2ada")
    val FTMS_TRAINING_STATUS = sig("2ad3")

    // Cycling Power Service (fallback for power/cadence)
    val CPS_SERVICE = sig("1818")
    val CPS_MEASUREMENT = sig("2a63")

    // Heart Rate Service
    val HR_SERVICE = sig("180d")
    val HR_MEASUREMENT = sig("2a37")

    // Battery & device information
    val BATTERY_SERVICE = sig("180f")
    val BATTERY_LEVEL = sig("2a19")
    val DEVICE_INFO_SERVICE = sig("180a")
    val FIRMWARE_REVISION = sig("2a26")
    val MANUFACTURER_NAME = sig("2a29")
    val MODEL_NUMBER = sig("2a24")

    // Client Characteristic Configuration Descriptor
    val CCCD = sig("2902")

    // Zwift controllers (Zwift Ride / Play / Click)
    val ZWIFT_RIDE_SERVICE = sig("fc82")
    const val ZWIFT_CUSTOM_SERVICE = "00000001-19ca-4651-86e5-fa29dcdd09d1"
    const val ZWIFT_ASYNC = "00000002-19ca-4651-86e5-fa29dcdd09d1"
    const val ZWIFT_SYNC_RX = "00000003-19ca-4651-86e5-fa29dcdd09d1"
    const val ZWIFT_SYNC_TX = "00000004-19ca-4651-86e5-fa29dcdd09d1"
}
