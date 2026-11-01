package se.spinhaus.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.spinhaus.app.ble.BleLog
import se.spinhaus.app.ble.DeviceHub
import se.spinhaus.app.data.ActivityRepository
import se.spinhaus.app.data.BackupManager
import se.spinhaus.app.data.DeviceRepository
import se.spinhaus.app.data.ProfileRepository
import se.spinhaus.app.data.SpinhausDatabase
import se.spinhaus.app.data.WorkoutRepository
import se.spinhaus.app.ride.RideController

/** Manual dependency container, created once by [SpinhausApp]. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = SpinhausDatabase.create(context)
    val profiles = ProfileRepository(db)
    val workouts = WorkoutRepository(db)
    val activities = ActivityRepository(db)
    val deviceRepo = DeviceRepository(db)
    val bleLog = BleLog()
    val hub = DeviceHub(context, scope, deviceRepo, bleLog)
    val ride = RideController(context, scope, hub, activities, profiles)
    val backup = BackupManager(context, db)

    private val prefs = context.getSharedPreferences("spinhaus", Context.MODE_PRIVATE)

    private val _currentProfileId = MutableStateFlow<Long?>(prefs.getLong(KEY_PROFILE, -1L).takeIf { it > 0 })
    /** Selected profile, or null when the profile picker should be shown. */
    val currentProfileId: StateFlow<Long?> = _currentProfileId

    private val _recovered = MutableStateFlow(0)
    /** Number of interrupted rides recovered at start-up (shown once). */
    val recovered: StateFlow<Int> = _recovered

    fun selectProfile(id: Long?) {
        _currentProfileId.value = id
        prefs.edit().putLong(KEY_PROFILE, id ?: -1L).apply()
        scope.launch {
            val p = id?.let { profiles.get(it) }
            hub.useHeartRate(p?.hrSensorAddress)
        }
    }

    fun onStartup() {
        scope.launch {
            _recovered.value = activities.recoverInterrupted(profiles)
        }
    }

    fun onBluetoothReady() {
        hub.startShared()
        scope.launch {
            val p = _currentProfileId.value?.let { profiles.get(it) }
            hub.useHeartRate(p?.hrSensorAddress)
        }
    }

    fun clearRecovered() { _recovered.value = 0 }

    companion object {
        private const val KEY_PROFILE = "profile"
    }
}
