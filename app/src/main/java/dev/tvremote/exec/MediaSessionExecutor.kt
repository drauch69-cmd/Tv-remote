package dev.tvremote.exec

import android.media.session.PlaybackState
import dev.tvremote.services.MediaListenerService

/**
 * Media control through the active MediaSession's transport controls.
 * Preferred over media keys because it can seek exactly ±10 s and knows
 * whether something is playing. Needs the notification-listener permission.
 */
class MediaSessionExecutor(private val watcher: MediaWatcher) : CommandExecutor {

    override val id = "media"

    override fun isAvailable() = MediaListenerService.connected

    override fun supports(name: String) = name in NAMES

    override fun execute(cmd: Command): Boolean {
        val c = watcher.controller() ?: return false // nothing to control: fall back to keys
        val controls = c.transportControls
        val state = c.playbackState
        val actions = state?.actions ?: 0L
        when (cmd.name) {
            "PLAY_PAUSE" ->
                if (state?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "NEXT" -> controls.skipToNext()
            "PREVIOUS" -> controls.skipToPrevious()
            "REWIND", "FAST_FORWARD" -> {
                val delta = if (cmd.name == "REWIND") -SEEK_MS else SEEK_MS
                if (state != null && actions and PlaybackState.ACTION_SEEK_TO != 0L) {
                    controls.seekTo((currentPosition(state) + delta).coerceAtLeast(0))
                } else if (cmd.name == "REWIND") controls.rewind() else controls.fastForward()
            }
            else -> return false
        }
        return true
    }

    /** Playback position now, accounting for time passed since the state was reported. */
    private fun currentPosition(state: PlaybackState): Long {
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val elapsed = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsed * state.playbackSpeed).toLong()
    }

    companion object {
        val NAMES = setOf("PLAY_PAUSE", "NEXT", "PREVIOUS", "REWIND", "FAST_FORWARD")
        const val SEEK_MS = 10_000L
    }
}
