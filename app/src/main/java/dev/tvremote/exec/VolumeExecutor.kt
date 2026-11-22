package dev.tvremote.exec

import android.content.Context
import android.media.AudioManager

/**
 * Volume through AudioManager. On a streaming box with HDMI-CEC volume control,
 * Android forwards these to the TV/soundbar itself; if that doesn't work on
 * your setup, the Shizuku key-event fallback (phase 4) is the next step.
 */
class VolumeExecutor(context: Context) : CommandExecutor {

    private val audio = context.getSystemService(AudioManager::class.java)

    override val id = "volume"

    override fun isAvailable() = true

    override fun supports(name: String) = name in DIRECTIONS

    override fun execute(cmd: Command): Boolean {
        val direction = DIRECTIONS[cmd.name] ?: return false
        // FLAG_SHOW_UI shows the TV's volume bar, so you can see each step.
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private companion object {
        val DIRECTIONS = mapOf(
            "VOLUME_UP" to AudioManager.ADJUST_RAISE,
            "VOLUME_DOWN" to AudioManager.ADJUST_LOWER,
            "MUTE" to AudioManager.ADJUST_TOGGLE_MUTE,
        )
    }
}
