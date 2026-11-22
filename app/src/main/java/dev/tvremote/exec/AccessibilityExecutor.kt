package dev.tvremote.exec

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_UP
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.tvremote.services.RemoteAccessibilityService

/**
 * Primary backend: Android 13+ accessibility global actions.
 * MENU has no global action, so here it opens Google TV search instead
 * (the Shizuku backend, phase 4, will send a real Menu key when available).
 */
class AccessibilityExecutor(private val context: Context) : CommandExecutor {

    override val id = "accessibility"

    private val actions = mapOf(
        "DPAD_UP" to GLOBAL_ACTION_DPAD_UP,
        "DPAD_DOWN" to GLOBAL_ACTION_DPAD_DOWN,
        "DPAD_LEFT" to GLOBAL_ACTION_DPAD_LEFT,
        "DPAD_RIGHT" to GLOBAL_ACTION_DPAD_RIGHT,
        "DPAD_CENTER" to GLOBAL_ACTION_DPAD_CENTER,
        "BACK" to GLOBAL_ACTION_BACK,
        "HOME" to GLOBAL_ACTION_HOME,
        "RECENTS" to GLOBAL_ACTION_RECENTS,
    )

    override fun isAvailable() = RemoteAccessibilityService.instance != null

    override fun supports(name: String) = name in actions || name == "MENU" || name == "SEARCH"

    override fun execute(cmd: Command): Boolean {
        val service = RemoteAccessibilityService.instance ?: return false
        actions[cmd.name]?.let { return service.performGlobalAction(it) }
        return openSearch(service)
    }

    /**
     * Opens the system search. Starting an activity from the background is
     * allowed here because the accessibility service is bound by the system.
     */
    private fun openSearch(service: Context): Boolean {
        val intents = listOf(
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                service.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w("AccessibilityExecutor", "Search intent failed: ${intent.action}", e)
            }
        }
        return false
    }
}
