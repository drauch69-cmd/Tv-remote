package dev.tvremote.shizuku

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Small wrapper around Shizuku: runs shell commands with ADB-level permissions.
 *
 * Used now to switch on our Accessibility service (Google TV hides the
 * "Allow restricted settings" step for sideloaded apps). Phase 4 builds the
 * Shizuku key-event backend on top of this.
 *
 * Shizuku has to be running (it needs re-starting after every TV reboot) and
 * TV Remote has to be granted permission in it once.
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    private const val REQUEST_CODE = 4242
    private val worker = Executors.newSingleThreadExecutor()

    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.isPreV11() -> State.NOT_RUNNING // very old Shizuku: not supported
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    } catch (e: Exception) {
        State.NOT_RUNNING
    }

    /** Shows Shizuku's "Allow TV Remote?" dialog; [onResult] gets true if allowed. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Result of a shell command. */
    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    /**
     * Runs [command] through `sh -c` as the shell user. Blocking: call off the main thread.
     * Shizuku made newProcess() private in API 13 but it still works; we reach it
     * by reflection, as many Shizuku apps do, to avoid the much bigger UserService setup.
     */
    fun exec(command: String): Result {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = process.inputStream.bufferedReader().readText() +
            process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        Log.i(TAG, "`$command` → $code ${out.trim()}")
        return Result(code, out.trim())
    }

    /**
     * Grants what's missing in one go: the Accessibility service ([a11yService])
     * and/or media-session access via our notification listener ([listenerService]).
     * Pass null for anything that's already on. [done] gets null on success.
     */
    fun grantAll(packageName: String, a11yService: String?, listenerService: String?, done: (String?) -> Unit) {
        if (a11yService == null && listenerService == null) return done(null)
        worker.execute {
            val errors = mutableListOf<String>()
            if (listenerService != null) {
                val r = try {
                    exec("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
                    exec("cmd notification allow_listener $packageName/$listenerService")
                } catch (e: Exception) { Result(1, e.message ?: "error") }
                if (!r.ok) errors += "media access: ${r.out}"
            }
            if (a11yService != null) enableAccessibilityBlocking(packageName, a11yService)?.let { errors += it }
            done(errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
        }
    }

    /**
     * Lifts the restricted-settings block and turns on our Accessibility service,
     * keeping any other services that are already on. Runs in the background;
     * [done] gets a short message for the user (null = success).
     */
    fun enableAccessibility(packageName: String, serviceClass: String, done: (String?) -> Unit) {
        worker.execute { done(enableAccessibilityBlocking(packageName, serviceClass)) }
    }

    /** The actual work for [enableAccessibility]; runs on the calling thread. */
    private fun enableAccessibilityBlocking(packageName: String, serviceClass: String): String? = try {
        if (state() != State.READY) {
            "Shizuku isn't running or TV Remote isn't allowed in it"
        } else {
            val component = "$packageName/$serviceClass"
            exec("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
            val current = exec("settings get secure enabled_accessibility_services").out
                .takeUnless { it.isBlank() || it == "null" }
            val list = current?.split(':')?.filter { it.isNotBlank() }.orEmpty()
            val merged = (list.filter { it != component } + component).joinToString(":")
            val put = exec("settings put secure enabled_accessibility_services '$merged'")
            exec("settings put secure accessibility_enabled 1")
            if (put.ok) null else "Shizuku command failed: ${put.out}"
        }
    } catch (e: Exception) {
        Log.e(TAG, "enableAccessibility failed", e)
        "Shizuku error: ${e.message}"
    }
}
