package dev.tvremote.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.tvremote.App
import dev.tvremote.ui.SettingsActivity

/**
 * Foreground service that keeps the web server alive while the TV is idle.
 * Started at boot, by the settings screen, and by the accessibility service.
 */
class RemoteService : Service() {

    private var server: WebServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        if (server?.isAlive != true) startServer()
        App.instance.updater.start()
        return START_STICKY // ask Android to restart us if we're killed
    }

    private fun startServer() {
        try {
            server?.stop()
            server = WebServer(App.instance, App.PORT).also {
                it.startServer()
                current = it
            }
            lastError = null
            Log.i(TAG, "Server listening on port ${App.PORT}")
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            Log.e(TAG, "Could not start server", e)
        }
        // Keeps Wi-Fi from dropping into power-save, so presses stay instant.
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvremote")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        server?.stop()
        current = null
        wifiLock?.release()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, App.CHANNEL_ID)
            .setContentTitle("TV Remote is running")
            .setContentText("Open http://${NetUtil.ipAddress() ?: "<tv-ip>"}:${App.PORT} on your phone")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RemoteService"
        private const val NOTIF_ID = 1

        /** The running server (for the settings screen), or null. */
        @Volatile var current: WebServer? = null
            private set
        @Volatile var lastError: String? = null
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RemoteService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start service", e)
            }
        }
    }
}
