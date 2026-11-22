package dev.tvremote.exec

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tvremote.services.MediaListenerService
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks the active media session (needs [MediaListenerService] enabled) and
 * reports "now playing" changes. Also hands out the current controller to
 * [MediaSessionExecutor].
 */
class MediaWatcher(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private var watched: MediaController? = null
    private var lastSent: String? = null

    /** The session to control: the one that's playing, else the most recent one. */
    fun controller(): MediaController? = try {
        val sessions = manager.getActiveSessions(listenerComponent)
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    } catch (e: SecurityException) {
        null // listener not enabled
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { rewatch() }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = rewatch()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = rewatch()
    }

    fun start() = main.post {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "No media session access", e)
        }
        rewatch()
    }

    fun stop() = main.post {
        try { manager.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) {}
        watched?.unregisterCallback(callback)
        watched = null
        publish()
    }

    /** Follows whichever session is now the most relevant. */
    private fun rewatch() {
        val c = controller()
        if (c?.sessionToken != watched?.sessionToken) {
            watched?.unregisterCallback(callback)
            watched = c
            c?.registerCallback(callback, main)
        }
        publish()
    }

    /** Current "now playing" as JSON, or null when nothing is playing. */
    fun nowPlaying(): JSONObject? {
        val c = watched ?: return null
        val meta = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val state = c.playbackState?.state
        if (title.isNullOrBlank() && state != PlaybackState.STATE_PLAYING) return null
        val subtitle = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        return JSONObject()
            .put("title", title ?: "")
            .put("subtitle", subtitle ?: "")
            .put("app", appLabel(c.packageName))
            .put("playing", state == PlaybackState.STATE_PLAYING)
    }

    private fun appLabel(pkg: String) = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { "" }

    private fun publish() {
        val json = nowPlaying()?.toString()
        if (json == lastSent) return
        lastSent = json
        listeners.forEach { it() }
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private companion object { const val TAG = "MediaWatcher" }
}
