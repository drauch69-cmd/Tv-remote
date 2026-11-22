package dev.tvremote.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dev.tvremote.App

/** Receives the result of an update install from Android's PackageInstaller. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("InstallResult", "status=$status msg=$msg")
        val showPrompt = App.instance.updater.onInstallResult(status, msg)
        if (showPrompt) {
            // Android's own "Do you want to update this app?" screen.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(confirm) } catch (e: Exception) {
                Log.w("InstallResult", "Couldn't show confirmation", e)
            }
        }
    }
}
