package dev.tvremote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.tvremote.data.Prefs
import dev.tvremote.exec.AccessibilityExecutor
import dev.tvremote.exec.CommandRouter
import dev.tvremote.exec.MediaKeyExecutor
import dev.tvremote.exec.MediaSessionExecutor
import dev.tvremote.exec.MediaWatcher
import dev.tvremote.exec.VolumeExecutor
import dev.tvremote.services.MediaListenerService
import dev.tvremote.pairing.PairingManager
import dev.tvremote.services.RemoteAccessibilityService
import dev.tvremote.shizuku.ShizukuHelper
import dev.tvremote.update.UpdateManager
import rikka.shizuku.Shizuku

/**
 * Holds the app-wide singletons. Everything is created once, here, so the
 * server, the accessibility service and the settings screen share one state.
 */
class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var pairing: PairingManager
        private set
    lateinit var router: CommandRouter
        private set
    lateinit var updater: UpdateManager
        private set
    lateinit var media: MediaWatcher
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        pairing = PairingManager(prefs)
        media = MediaWatcher(this)
        // Order matters: the router asks each backend in turn and uses the
        // first one that is available and supports the command.
        router = CommandRouter(
            listOf(
                AccessibilityExecutor(this),
                MediaSessionExecutor(media), // exact seeking when we can see the session
                MediaKeyExecutor(this),      // otherwise plain media keys
                VolumeExecutor(this),
            )
        )
        updater = UpdateManager(this)
        // When Shizuku becomes available, use it to switch our Accessibility
        // service back on if it's off (e.g. after an update or a fresh install).
        Shizuku.addBinderReceivedListenerSticky { ensurePermissionsViaShizuku() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote server", NotificationManager.IMPORTANCE_MIN)
        )
    }

    /**
     * If Shizuku is ready, quietly grants whatever is still missing: the
     * Accessibility service and media-session (notification listener) access.
     */
    fun ensurePermissionsViaShizuku(done: (String?) -> Unit = {}) {
        if (ShizukuHelper.state() != ShizukuHelper.State.READY) return done("Shizuku isn't ready")
        ShizukuHelper.grantAll(
            packageName,
            RemoteAccessibilityService::class.java.name.takeIf { RemoteAccessibilityService.instance == null },
            MediaListenerService::class.java.name.takeIf { !MediaListenerService.connected },
            done,
        )
    }

    companion object {
        const val CHANNEL_ID = "server"
        const val PORT = 8765
        lateinit var instance: App
            private set
    }
}
