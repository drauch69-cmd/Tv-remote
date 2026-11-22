package dev.tvremote.exec

import android.util.Log
import android.view.KeyEvent
import dev.tvremote.shizuku.ShizukuHelper
import java.util.concurrent.Executors

/**
 * Sends input through Shizuku (`input keyevent`, `input text`, `am start`), i.e.
 * exactly like a real remote/keyboard. Each `input` call starts a small program
 * on the TV (~0.2–0.5 s), so presses that arrive while one is running are
 * batched into the next call — holding Volume up stays responsive.
 */
object ShizukuInput {
    private const val TAG = "ShizukuInput"
    private val worker = Executors.newSingleThreadExecutor()
    private val pendingKeys = ArrayList<Int>()

    fun ready() = ShizukuHelper.state() == ShizukuHelper.State.READY

    /** Queues a key press; consecutive presses are sent together in one `input` call. */
    fun key(code: Int) {
        val startDrain = synchronized(pendingKeys) {
            pendingKeys += code
            pendingKeys.size == 1
        }
        if (startDrain) worker.execute(::drainKeys)
    }

    private fun drainKeys() {
        while (true) {
            val batch = synchronized(pendingKeys) {
                if (pendingKeys.isEmpty()) return
                ArrayList(pendingKeys).also { pendingKeys.clear() }
            }
            run("input keyevent ${batch.joinToString(" ")}")
        }
    }

    /** Runs a shell command in order with key presses (fire-and-forget). */
    fun shell(command: String) = worker.execute { run(command) }

    private fun run(command: String) {
        try {
            ShizukuHelper.exec(command)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku command failed: $command", e)
        }
    }

    /** Quotes [s] for `sh` and encodes spaces the way `input text` wants them. */
    fun inputTextArg(s: String) = "'" + s.replace("'", "'\\''").replace(" ", "%s") + "'"
}

/**
 * High-priority Shizuku backend: real key events for things the other
 * backends can't do well. Volume goes here first because only real volume
 * keys are forwarded to the TV/soundbar over HDMI-CEC.
 */
class ShizukuKeyExecutor : CommandExecutor {
    override val id = "shizuku"
    override fun isAvailable() = ShizukuInput.ready()
    override fun supports(name: String) = name in KEYS || name == "KEY"

    override fun execute(cmd: Command): Boolean {
        val code = if (cmd.name == "KEY") cmd.args.optInt("code", -1) else KEYS.getValue(cmd.name)
        if (code !in 0..KeyEvent.getMaxKeyCode()) throw ExecError("Bad key code")
        ShizukuInput.key(code)
        return true
    }

    companion object {
        val KEYS = mapOf(
            "VOLUME_UP" to KeyEvent.KEYCODE_VOLUME_UP,
            "VOLUME_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
            "MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "MENU" to KeyEvent.KEYCODE_MENU,
            "SETTINGS" to KeyEvent.KEYCODE_SETTINGS,
            "SLEEP" to KeyEvent.KEYCODE_SLEEP,
            "POWER" to KeyEvent.KEYCODE_POWER,
            "SEARCH" to KeyEvent.KEYCODE_SEARCH,
        )
    }
}

/**
 * Low-priority Shizuku backend: typing and app launching when the
 * Accessibility service can't (e.g. YouTube's own on-screen keyboard has no
 * real text field). Typing is mirrored by deleting/adding only what changed.
 */
class ShizukuFallbackExecutor(private val launcher: AppLauncher) : CommandExecutor {
    override val id = "shizuku"
    override fun isAvailable() = ShizukuInput.ready()
    override fun supports(name: String) = name in setOf("TEXT", "TEXT_CLEAR", "TEXT_ENTER", "LAUNCH")

    /** What we believe the TV field contains (what we've typed so far). */
    private var typed = ""

    override fun execute(cmd: Command): Boolean {
        when (cmd.name) {
            "TEXT" -> typeTo(cmd.args.optString("text"))
            "TEXT_CLEAR" -> {
                // Select all + delete, so it works even if we don't know the contents.
                ShizukuInput.shell(
                    "input keycombination ${KeyEvent.KEYCODE_CTRL_LEFT} ${KeyEvent.KEYCODE_A}; " +
                        "input keyevent ${KeyEvent.KEYCODE_DEL}"
                )
                typed = ""
            }
            "TEXT_ENTER" -> ShizukuInput.key(KeyEvent.KEYCODE_ENTER)
            "LAUNCH" -> {
                val pkg = cmd.args.optString("pkg")
                val component = launcher.launchIntent(pkg)?.component
                    ?: throw ExecError("App not found: $pkg")
                ShizukuInput.shell("am start -n ${component.flattenToShortString()}")
            }
        }
        return true
    }

    @Synchronized
    private fun typeTo(target: String) {
        val common = typed.commonPrefixWith(target).length
        val deletes = typed.length - common
        val add = target.substring(common)
        val parts = mutableListOf<String>()
        if (deletes > 0) parts += "input keyevent " + List(deletes) { KeyEvent.KEYCODE_DEL }.joinToString(" ")
        if (add.isNotEmpty()) parts += "input text " + ShizukuInput.inputTextArg(add)
        if (parts.isNotEmpty()) ShizukuInput.shell(parts.joinToString("; "))
        typed = target
    }
}
