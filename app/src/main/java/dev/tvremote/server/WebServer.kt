package dev.tvremote.server

import android.content.Context
import android.util.Log
import dev.tvremote.App
import dev.tvremote.exec.Command
import dev.tvremote.pairing.CodeOverlay
import dev.tvremote.pairing.PairingManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * HTTP + WebSocket server.
 *  - GET /            → the phone remote page (from assets/web/)
 *  - GET /<file>      → other page assets
 *  - WS  /ws          → commands; see docs/protocol.md
 */
class WebServer(private val context: Context, port: Int) : NanoWSD(port) {

    private val app = App.instance
    private val clients = CopyOnWriteArraySet<Client>()

    /** Phones currently connected and paired. */
    val pairedClientCount get() = clients.count { it.authed }

    private val capsListener: () -> Unit = { broadcast(capsMessage("caps")) }
    private val updateListener: () -> Unit = { broadcast(updateMessage()) }

    fun startServer() {
        // Timeout 0 = never time out idle sockets (NanoHTTPD's default 5 s would
        // drop the WebSocket between presses).
        start(0, false)
        app.router.addCapsListener(capsListener)
        app.updater.addListener(updateListener)
        app.pairing.onCodeChanged = { code ->
            if (code != null) CodeOverlay.show(code) else CodeOverlay.hide()
        }
    }

    override fun stop() {
        app.router.removeCapsListener(capsListener)
        app.updater.removeListener(updateListener)
        clients.forEach { it.closeQuietly() }
        super.stop()
    }

    /** Kicks every phone off (used by "Forget all paired phones"). */
    fun kickAll() {
        clients.forEach {
            it.sendJson(JSONObject().put("t", "auth").put("ok", false))
            it.closeQuietly()
        }
    }

    // ---------------------------------------------------------------- HTTP

    override fun serveHttp(session: IHTTPSession): Response {
        var path = session.uri.trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path.contains("..")) return notFound()
        return try {
            val stream = context.assets.open("web/$path")
            NanoHTTPD.newChunkedResponse(Response.Status.OK, mimeFor(path), stream).apply {
                addHeader("Cache-Control", "no-cache")
            }
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun notFound() =
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")

    private fun mimeFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "wav", "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    // ----------------------------------------------------------- WebSocket

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Client(handshake)

    private fun broadcast(json: JSONObject) = clients.filter { it.authed }.forEach { it.sendJson(json) }

    private fun capsMessage(type: String) =
        JSONObject().put("t", type).put("caps", app.router.caps())

    private fun updateMessage() = JSONObject().put("t", "update").put("update", app.updater.toJson())

    /** One connected phone. */
    inner class Client(handshake: IHTTPSession) : WebSocket(handshake) {
        @Volatile var authed = false

        override fun onOpen() { clients += this }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients -= this
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            Log.d(TAG, "Socket error: ${exception?.message}")
            clients -= this
        }

        override fun onMessage(frame: WebSocketFrame) {
            val msg = try { JSONObject(frame.textPayload) } catch (e: Exception) { return }
            when (msg.optString("t")) {
                "ping" -> sendJson(JSONObject().put("t", "pong"))
                "hello" -> {
                    authed = app.pairing.isValidToken(msg.optString("token"))
                    if (authed) {
                        sendJson(capsMessage("auth").put("ok", true).put("name", deviceName()))
                        sendJson(updateMessage())
                    } else sendJson(JSONObject().put("t", "auth").put("ok", false))
                }
                "pair_request" -> {
                    val ttl = app.pairing.requestCode()
                    if (ttl < 0) sendJson(pairFailed("locked"))
                    else sendJson(JSONObject().put("t", "pair_started").put("ttl", ttl))
                }
                "pair" -> handlePair(msg.optString("code"))
                "cmd" -> handleCommand(msg)
                "update_check" -> if (authed) app.updater.checkNow()
                "update_install" -> if (authed) app.updater.installNow()
            }
        }

        private fun handlePair(code: String) {
            when (val r = app.pairing.verify(code)) {
                is PairingManager.Result.Ok -> {
                    authed = true
                    sendJson(capsMessage("paired").put("token", r.token))
                    sendJson(updateMessage())
                }
                is PairingManager.Result.WrongCode -> sendJson(pairFailed("wrong_code").put("left", r.attemptsLeft))
                PairingManager.Result.Expired -> sendJson(pairFailed("expired"))
                PairingManager.Result.Locked -> sendJson(pairFailed("locked"))
            }
        }

        private fun handleCommand(msg: JSONObject) {
            if (!authed) {
                sendJson(JSONObject().put("t", "auth").put("ok", false))
                return
            }
            val name = msg.optString("c")
            app.updater.lastActivity = android.os.SystemClock.elapsedRealtime()
            val error = try {
                app.router.run(Command(name, msg))
            } catch (e: Exception) {
                Log.e(TAG, "Command $name crashed", e)
                "$name failed: ${e.message}"
            }
            // Auto-repeat presses fail quietly so the phone isn't flooded with toasts.
            if (error != null && !msg.optBoolean("repeat")) {
                sendJson(JSONObject().put("t", "err").put("c", name).put("msg", error))
            }
        }

        private fun pairFailed(reason: String) =
            JSONObject().put("t", "pair_failed").put("reason", reason)

        fun sendJson(json: JSONObject) {
            try {
                synchronized(this) { send(json.toString()) }
            } catch (e: IOException) {
                clients -= this
            }
        }

        fun closeQuietly() {
            try { close(WebSocketFrame.CloseCode.NormalClosure, "bye", false) } catch (_: Exception) {}
            clients -= this
        }
    }

    private fun deviceName() = android.os.Build.MODEL ?: "TV"

    private companion object { const val TAG = "WebServer" }
}
