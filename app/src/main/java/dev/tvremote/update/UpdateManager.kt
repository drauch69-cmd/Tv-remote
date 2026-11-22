package dev.tvremote.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Keeps the app up to date from GitHub Releases.
 *
 *  1. Every [CHECK_EVERY_MS] (and at start-up) it asks GitHub for the latest
 *     release, tagged "build-<N>". N is the CI run number, which is also our versionCode.
 *  2. If N is newer, it downloads tv-remote.apk in the background.
 *  3. It installs once the TV looks idle (see [isTvIdle]), or right away when
 *     someone presses "Install now" on the TV or the phone.
 *
 * Android only accepts the APK if it's signed with the same key as the
 * installed app, so a tampered download can't be installed.
 */
class UpdateManager(private val context: Context) {

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, ERROR }

    @Volatile var phase = Phase.IDLE; private set
    @Volatile var latestBuild = 0; private set
    @Volatile var message: String? = null; private set
    @Volatile private var lastCheck = 0L
    @Volatile private var installStartedAt = 0L
    /** True when the last install was started by a button press (someone is at the TV). */
    @Volatile var userRequested = false; private set
    /** Android wants a tap to confirm (first self-update): don't auto-install, wait for a button. */
    @Volatile private var needsConfirm = false

    val currentBuild: Int = context.packageManager
        .getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    private val thread = HandlerThread("updater").apply { start() }
    private val bg = Handler(thread.looper)
    private val apkFile get() = File(context.cacheDir, "update.apk")

    /** Last time a phone sent a command; used to tell whether someone is watching. */
    @Volatile var lastActivity = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------ schedule

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (phase != Phase.DOWNLOADING && phase != Phase.INSTALLING &&
                (lastCheck == 0L || now - lastCheck > CHECK_EVERY_MS)) {
                check()
            }
            // An install that never reported back (prompt dismissed, etc.): try again later.
            if (phase == Phase.INSTALLING && now - installStartedAt > STUCK_AFTER_MS) set(Phase.READY)
            if (phase == Phase.READY && !needsConfirm && isTvIdle()) install(byUser = false)
            bg.postDelayed(this, TICK_MS)
        }
    }

    /** Starts the background schedule. Safe to call more than once. */
    fun start() {
        bg.removeCallbacks(tick)
        bg.postDelayed(tick, FIRST_CHECK_DELAY_MS) // let Wi-Fi come up after boot
    }

    /** "Check for updates" button. Downloads too, if there's something new. */
    fun checkNow() = bg.post { check() }

    /** "Install now" button: installs a downloaded update right away. */
    fun installNow() = bg.post {
        if (phase != Phase.READY) check()
        if (phase == Phase.READY) install(byUser = true)
    }

    // --------------------------------------------------------------- steps

    private fun check() {
        lastCheck = SystemClock.elapsedRealtime()
        set(Phase.CHECKING)
        try {
            val json = JSONObject(httpGet(LATEST_URL))
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull()
                ?: return set(Phase.ERROR, "Latest release has no build number")
            latestBuild = build
            if (build <= currentBuild) {
                apkFile.delete()
                return set(Phase.UP_TO_DATE)
            }
            val assets = json.optJSONArray("assets")
            val url = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "tv-remote.apk" }
                ?.optString("browser_download_url")
                ?: return set(Phase.ERROR, "Build $build has no APK attached")
            download(url)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            set(Phase.ERROR, "Couldn't reach GitHub: ${e.message}")
        }
    }

    private fun download(url: String) {
        set(Phase.DOWNLOADING)
        val tmp = File(context.cacheDir, "update.apk.part")
        open(url).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        // Make sure it's the build we expect before keeping it.
        val info = context.packageManager.getPackageArchiveInfo(tmp.path, 0)
        if (info?.packageName != context.packageName) {
            tmp.delete()
            return set(Phase.ERROR, "Downloaded file isn't a TV Remote APK")
        }
        tmp.renameTo(apkFile)
        set(Phase.READY)
    }

    private fun install(byUser: Boolean) {
        if (!apkFile.exists()) return set(Phase.ERROR, "Update file missing; checking again later")
        userRequested = byUser
        installStartedAt = SystemClock.elapsedRealtime()
        set(Phase.INSTALLING)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                // Android 12+: once this app is the one that installed itself,
                // later updates go through without a confirmation prompt.
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            // If it succeeds, Android kills this process and restarts us on the new build.
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            set(Phase.ERROR, "Install failed: ${e.message}")
        }
    }

    /**
     * Called by [InstallResultReceiver] when Android reports how the install went.
     * Returns true if the confirmation screen should be shown on the TV.
     */
    fun onInstallResult(status: Int, msg: String?): Boolean {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> set(Phase.UP_TO_DATE)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (userRequested) {
                    set(Phase.INSTALLING, "Confirm the update on the TV")
                    return true
                }
                // Nobody's at the TV to tap "Update": wait for a button press.
                needsConfirm = true
                set(Phase.READY, "This update needs a one-time confirmation: press Install now")
            }
            else -> set(Phase.READY, "Install didn't finish (${msg ?: status}); will retry")
        }
        return false
    }

    /**
     * Idle = nobody is using the TV: the screen is off, or no audio is playing
     * and no phone command has arrived for [IDLE_AFTER_MS].
     */
    private fun isTvIdle(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isInteractive) return true
        val audio = context.getSystemService(AudioManager::class.java)
        val quiet = SystemClock.elapsedRealtime() - lastActivity > IDLE_AFTER_MS
        return quiet && !audio.isMusicActive
    }

    // ------------------------------------------------------------- helpers

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tv-remote-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
        }

    private fun httpGet(url: String) = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun set(p: Phase, msg: String? = null) {
        phase = p
        message = msg
        listeners.forEach { it() }
    }

    /** What the phone and the settings screen show. */
    fun toJson(): JSONObject = JSONObject()
        .put("phase", phase.name.lowercase())
        .put("current", currentBuild)
        .put("latest", latestBuild)
        .put("msg", message ?: JSONObject.NULL)

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    companion object {
        private const val TAG = "UpdateManager"
        const val REPO = "drauch69-cmd/Tv-remote"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
        private const val TICK_MS = 5 * 60_000L
        private const val FIRST_CHECK_DELAY_MS = 60_000L
        private const val IDLE_AFTER_MS = 30 * 60_000L
        private const val STUCK_AFTER_MS = 10 * 60_000L
    }
}
