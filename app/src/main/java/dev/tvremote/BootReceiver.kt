package dev.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.tvremote.server.RemoteService

/** Starts the server when the TV boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemoteService.start(context)
    }
}
