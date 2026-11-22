package dev.tvremote.pairing

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.App
import dev.tvremote.pairing.PairingManager.Companion.CODE_TTL_MS
import dev.tvremote.services.RemoteAccessibilityService

/**
 * Shows the pairing code on the TV.
 *  - With the accessibility service on: a card in the top-right corner
 *    (TYPE_ACCESSIBILITY_OVERLAY needs no extra permission).
 *  - Without it: a long toast, and the code is also shown in the app's settings screen.
 */
object CodeOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val autoHide = Runnable { hideNow() }

    fun show(code: String) = main.post {
        hideNow()
        val service = RemoteAccessibilityService.instance
        if (service == null) {
            Toast.makeText(App.instance, "TV Remote pairing code: $code", Toast.LENGTH_LONG).show()
            return@post
        }
        val wm = service.getSystemService(WindowManager::class.java)
        val card = buildCard(service, code)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Don't steal focus or key presses from whatever is on screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 48; y = 48
        }
        try {
            wm.addView(card, lp)
            view = card
            main.postDelayed(autoHide, CODE_TTL_MS)
        } catch (e: Exception) {
            Toast.makeText(App.instance, "TV Remote pairing code: $code", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() = main.post { hideNow() }

    private fun hideNow() {
        main.removeCallbacks(autoHide)
        val v = view ?: return
        view = null
        try {
            RemoteAccessibilityService.instance
                ?.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Exception) { }
    }

    private fun buildCard(ctx: android.content.Context, code: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 36)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1A2140"), Color.parseColor("#11162B"))
            ).apply {
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#6C7BFF"))
            }
            addView(TextView(ctx).apply {
                text = "Pair your phone"
                setTextColor(Color.parseColor("#8A90B8"))
                textSize = 16f
            })
            addView(TextView(ctx).apply {
                text = code.toCharArray().joinToString(" ")
                setTextColor(Color.WHITE)
                textSize = 44f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
        }
}
