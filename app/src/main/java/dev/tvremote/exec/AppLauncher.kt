package dev.tvremote.exec

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Lists launchable TV apps, renders their banners/icons, and builds launch intents. */
class AppLauncher(private val context: Context) {

    data class AppInfo(val pkg: String, val label: String, val banner: Boolean)

    private val pm get() = context.packageManager
    private val images = ConcurrentHashMap<String, ByteArray>()

    /** Every app with a TV (Leanback) or phone launcher entry, sorted by name. */
    fun list(): List<AppInfo> {
        val found = LinkedHashMap<String, AppInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || pkg in found) continue
                found[pkg] = AppInfo(pkg, ri.loadLabel(pm).toString(), banner(pkg) != null)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** The intent that opens [pkg], preferring its TV entry point. */
    fun launchIntent(pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** PNG of the app's TV banner (320×180) or, if it has none, its icon (192×192). Cached. */
    fun image(pkg: String): ByteArray? = images[pkg] ?: try {
        val banner = banner(pkg)
        val png = if (banner != null) render(banner, 320, 180) else render(pm.getApplicationIcon(pkg), 192, 192)
        png.also { images[pkg] = it }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun banner(pkg: String): Drawable? = try {
        pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
            ?: pm.getApplicationBanner(pkg)
    } catch (_: Exception) { null }

    private fun render(d: Drawable, w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
