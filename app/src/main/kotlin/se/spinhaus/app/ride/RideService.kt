package se.spinhaus.app.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import se.spinhaus.app.MainActivity
import se.spinhaus.app.R

/** Foreground service that keeps the process (and Bluetooth) alive during a ride. */
class RideService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: the system restarted us after process death – there is no ride anymore.
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.ride_in_progress))
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Missing Bluetooth permission on Android 14+: run without the foreground service.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spinhaus:ride").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            val granted = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
            runCatching { context.startForegroundService(Intent(context, RideService::class.java).setAction("start")) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RideService::class.java)) }
        }
    }
}
