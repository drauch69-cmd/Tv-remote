package dev.tvremote.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.App
import dev.tvremote.server.NetUtil
import dev.tvremote.server.RemoteService
import dev.tvremote.services.MediaListenerService
import dev.tvremote.services.RemoteAccessibilityService
import dev.tvremote.shizuku.ShizukuHelper
import dev.tvremote.update.UpdateManager

/**
 * The TV-side screen: address to open on the phone, status of each control
 * method, and buttons to grant permissions. Built in code (no XML/AppCompat)
 * to keep the app small. Everything is reachable with the TV remote's D-pad.
 */
class SettingsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var address: TextView
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var update: TextView

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteService.start(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(96, 64, 96, 64)
        }
        col.addView(text("TV Remote", 34f, Color.WHITE, bold = true))
        col.addView(text("On your iPhone, open this in Safari, then Share → Add to Home Screen:", 18f, MUTED).padTop(24))
        address = text("", 30f, ACCENT, bold = true).also { col.addView(it) }
        code = text("", 22f, Color.WHITE, bold = true).padTop(12).also { col.addView(it) }
        status = text("", 18f, Color.WHITE).padTop(24).also { col.addView(it) }

        col.addView(text("Permissions", 22f, Color.WHITE, bold = true).padTop(40))
        col.addView(button("Set up permissions with Shizuku") { enableWithShizuku() })
        col.addView(text(
            "Easiest way on Google TV: start Shizuku, press this, and allow TV Remote when Shizuku asks. " +
            "It turns on the Accessibility service and media access (now playing, exact seeking).",
            15f, MUTED,
        ).padTop(8))
        col.addView(button("Open Accessibility settings") {
            startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(text(
            "If TV Remote is greyed out there (\"restricted setting\"), open App info below, " +
            "press the ⋮ menu → Allow restricted settings, then try again. " +
            "If your TV has no such menu, use the ADB commands in the README.",
            15f, MUTED,
        ).padTop(8))
        col.addView(button("Open App info") {
            startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(text("Updates", 22f, Color.WHITE, bold = true).padTop(40))
        update = text("", 18f, Color.WHITE).padTop(8).also { col.addView(it) }
        col.addView(button("Check for updates") { App.instance.updater.checkNow() })
        col.addView(button("Install now") { App.instance.updater.installNow() })
        col.addView(button("Allow this app to install updates") {
            startSafely(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        col.addView(text(
            "Updates download automatically and install when the TV is idle. " +
            "The first update asks you to confirm on the TV; after that they install silently.",
            15f, MUTED,
        ).padTop(8))

        col.addView(text("Paired phones", 22f, Color.WHITE, bold = true).padTop(40))
        col.addView(button("Forget all paired phones") {
            App.instance.pairing.forgetAllPhones()
            RemoteService.current?.kickAll()
            Toast.makeText(this, "All phones must pair again", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(col)
        })
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { handler.removeCallbacks(refresher); super.onPause() }

    private fun refresh() {
        val ip = NetUtil.ipAddress()
        address.text = if (ip != null) "http://$ip:${App.PORT}" else "No network connection"

        val pairCode = App.instance.pairing.activeCode
        code.visibility = if (pairCode != null) View.VISIBLE else View.GONE
        code.text = "Pairing code: ${pairCode?.toCharArray()?.joinToString(" ")}"

        val server = RemoteService.current
        val lines = mutableListOf<String>()
        lines += when {
            server?.isAlive == true -> "● Server running · ${server?.pairedClientCount ?: 0} phone(s) connected"
            RemoteService.lastError != null -> "✕ Server error: ${RemoteService.lastError}"
            else -> "… Server starting"
        }
        lines += mark(RemoteAccessibilityService.instance != null) +
            " Accessibility service (D-pad, Back, Home, Recents)"
        lines += mark(MediaListenerService.connected) +
            " Media access (now playing, 10-second seek) · play/pause works either way"
        lines += "✓ Volume
        lines += when (ShizukuHelper.state()) {
            ShizukuHelper.State.READY -> "✓ Shizuku ready"
            ShizukuHelper.State.NO_PERMISSION -> "✕ Shizuku running, TV Remote not allowed yet"
            ShizukuHelper.State.NOT_RUNNING -> "✕ Shizuku not running (optional)"
        }
        status.text = lines.joinToString("\n")

        val u = App.instance.updater
        val phaseText = when (u.phase) {
            UpdateManager.Phase.IDLE -> "Not checked yet"
            UpdateManager.Phase.CHECKING -> "Checking…"
            UpdateManager.Phase.UP_TO_DATE -> "Up to date"
            UpdateManager.Phase.DOWNLOADING -> "Downloading build ${u.latestBuild}…"
            UpdateManager.Phase.READY -> "Build ${u.latestBuild} ready – installs when the TV is idle"
            UpdateManager.Phase.INSTALLING -> "Installing build ${u.latestBuild}…"
            UpdateManager.Phase.ERROR -> "Update problem"
        }
        val canInstall = if (packageManager.canRequestPackageInstalls()) "" else
            "\n✕ Not allowed to install updates yet (button below)"
        update.text = "This is build ${u.currentBuild} · $phaseText" +
            (u.message?.let { "\n$it" } ?: "") + canInstall
    }

    private fun enableWithShizuku() {
        when (ShizukuHelper.state()) {
            ShizukuHelper.State.NOT_RUNNING -> toast("Start Shizuku first, then press this again")
            ShizukuHelper.State.NO_PERMISSION -> ShizukuHelper.requestPermission { granted ->
                if (granted) runEnable() else runOnUiThread { toast("TV Remote wasn't allowed in Shizuku") }
            }
            ShizukuHelper.State.READY -> runEnable()
        }
    }

    private fun runEnable() {
        if (RemoteAccessibilityService.instance != null && MediaListenerService.connected) {
            return toast("Everything is already on ✓")
        }
        App.instance.ensurePermissionsViaShizuku { err ->
            runOnUiThread { toast(err ?: "Permissions turned on ✓") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ helpers

    private fun mark(on: Boolean) = if (on) "✓" else "✕"

    private fun startSafely(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "This TV doesn't have that screen. See the README.", Toast.LENGTH_LONG).show()
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun TextView.padTop(px: Int) = apply { setPadding(0, px, 0, 0) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 20f
            setColor(SURFACE)
        }
        // Visible focus ring for D-pad navigation.
        setOnFocusChangeListener { v, focused ->
            (v.background as GradientDrawable).setStroke(if (focused) 4 else 0, ACCENT)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 }
        setPadding(40, 20, 40, 20)
    }

    private companion object {
        val BG = Color.parseColor("#0B0F1E")
        val SURFACE = Color.parseColor("#1A2140")
        val ACCENT = Color.parseColor("#7C86FF")
        val MUTED = Color.parseColor("#8A90B8")
    }
}
