package dev.tvremote.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.tvremote.App
import dev.tvremote.server.RemoteService

/**
 * The accessibility service. It doesn't watch the screen; it exists so we can
 * call performGlobalAction() (D-pad, Back, Home, Recents), draw the pairing
 * code overlay, and start activities from the background.
 */
class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        App.instance.router.notifyCapsChanged()
        // The system binds this service at boot too, so it doubles as a
        // second way of making sure the server is running.
        RemoteService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        instance = null
        App.instance.router.notifyCapsChanged()
        super.onDestroy()
    }

    companion object {
        /** The running service, or null when it's turned off. */
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }
}
