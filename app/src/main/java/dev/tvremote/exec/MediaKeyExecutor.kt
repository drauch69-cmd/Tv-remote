package dev.tvremote.exec

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Fallback media control: sends media key presses to whatever is playing,
 * like a Bluetooth remote would. Needs no permission at all.
 */
class MediaKeyExecutor(context: Context) : CommandExecutor {

    private val audio = context.getSystemService(AudioManager::class.java)

    override val id = "mediakeys"

    override fun isAvailable() = true

    override fun supports(name: String) = name in KEYS

    override fun execute(cmd: Command): Boolean {
        val code = KEYS[cmd.name] ?: return false
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return true
    }

    private companion object {
        val KEYS = mapOf(
            "PLAY_PAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "NEXT" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "PREVIOUS" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "REWIND" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "FAST_FORWARD" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )
    }
}
