package dev.tvremote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.tvremote.data.Prefs
import dev.tvremote.exec.AccessibilityExecutor
import dev.tvremote.exec.CommandRouter
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        pairing = PairingManager(prefs)
        // Order matters: the router asks each backend in turn and uses the
        // first one that is available and supports the command.
        router = CommandRouter(
            listOf(
                AccessibilityExecutor(this),
            )
        )
        updater = UpdateManager(this)
        // When Shizuku becomes available, use it to switch our Accessibility
        // service back on if it's off (e.g. after an update or a fresh install).
        Shizuku.addBinderReceivedListenerSticky { ensureAccessibilityViaShizuku() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote server", NotificationManager.IMPORTANCE_MIN)
        )
    }

    /** Turns on the Accessibility service through Shizuku, if it's off and Shizuku is ready. */
    fun ensureAccessibilityViaShizuku() {
        if (RemoteAccessibilityService.instance != null) return
        if (ShizukuHelper.state() != ShizukuHelper.State.READY) return
        ShizukuHelper.enableAccessibility(packageName, RemoteAccessibilityService::class.java.name) {}
    }

    companion object {
        const val CHANNEL_ID = "server"
        const val PORT = 8765
        lateinit var instance: App
            private set
    }
}
