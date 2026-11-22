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
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.tvremote.services.RemoteAccessibilityService

/**
 * Primary backend: Android 13+ accessibility global actions.
 * MENU has no global action, so here it opens Google TV search instead
 * (the Shizuku backend, phase 4, will send a real Menu key when available).
 */
class AccessibilityExecutor(
    private val context: Context,
    private val launcher: AppLauncher,
) : CommandExecutor {

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

    override fun supports(name: String) =
        name in actions || name in setOf("MENU", "SEARCH", "TEXT", "TEXT_ENTER", "TEXT_CLEAR", "LAUNCH")

    override fun execute(cmd: Command): Boolean {
        val service = RemoteAccessibilityService.instance ?: return false
        actions[cmd.name]?.let { return service.performGlobalAction(it) }
        return when (cmd.name) {
            "TEXT" -> setText(service, cmd.args.optString("text"))
            "TEXT_CLEAR" -> setText(service, "")
            "TEXT_ENTER" -> pressEnter(service)
            "LAUNCH" -> launch(service, cmd.args.optString("pkg"))
            else -> openSearch(service)
        }
    }

    // ------------------------------------------------------------ text input

    /** The text field that currently has focus on the TV, if any. */
    private fun focusedField(service: RemoteAccessibilityService): AccessibilityNodeInfo? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        // Some apps don't report input focus; look for a focused editable node instead.
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return service.windows.firstNotNullOfOrNull { search(it.root) }
    }

    /** Replaces the focused field's contents with [text]. */
    private fun setText(service: RemoteAccessibilityService, text: String): Boolean {
        val field = focusedField(service) ?: throw ExecError("No text field is selected on the TV")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        // Returning false lets the Shizuku typing fallback have a go.
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the keyboard's Enter / Search / Go action on the focused field. */
    private fun pressEnter(service: RemoteAccessibilityService): Boolean {
        val field = focusedField(service) ?: return false
        return field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // ---------------------------------------------------------- app launching

    /** Allowed from the background because the system binds this accessibility service. */
    private fun launch(service: RemoteAccessibilityService, pkg: String): Boolean {
        val intent = launcher.launchIntent(pkg) ?: throw ExecError("App not found: $pkg")
        return try {
            service.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("AccessibilityExecutor", "Launch failed for $pkg", e)
            false
        }
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
