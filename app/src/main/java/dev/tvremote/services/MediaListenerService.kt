package dev.tvremote.services

import android.service.notification.NotificationListenerService
import dev.tvremote.App

/**
 * Notification listener. We don't read notifications; Android simply requires
 * an enabled listener before it lets us see other apps' media sessions
 * (now-playing info, seeking). Granted via Shizuku or ADB on Google TV.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        App.instance.media.start()
        App.instance.router.notifyCapsChanged()
    }

    override fun onListenerDisconnected() {
        connected = false
        App.instance.media.stop()
        App.instance.router.notifyCapsChanged()
    }

    companion object {
        @Volatile var connected = false
            private set
    }
}
