package dev.tvremote.exec

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Sends each command to the best backend that can handle it. */
class CommandRouter(private val executors: List<CommandExecutor>) {

    /** Result of running a command: null = OK, otherwise an error message for the phone. */
    fun run(cmd: Command): String? {
        val candidates = executors.filter { it.supports(cmd.name) }
        if (candidates.isEmpty()) return "Unknown command ${cmd.name}"
        for (ex in candidates) {
            if (!ex.isAvailable()) continue
            if (ex.execute(cmd)) return null
        }
        return if (candidates.none { it.isAvailable() })
            "No control method is turned on for ${cmd.name}"
        else
            "${cmd.name} failed"
    }

    /** What works right now, sent to the phone so it can grey out buttons. */
    fun caps(): JSONObject {
        val json = JSONObject()
        for (ex in executors) json.put(ex.id, ex.isAvailable())
        for (id in listOf("accessibility", "media", "shizuku")) if (!json.has(id)) json.put(id, false)
        json.put("menu", if (json.optBoolean("shizuku")) "menu" else "search")
        return json
    }

    // --- change notifications (backends call this when they turn on/off) ---

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addCapsListener(l: () -> Unit) { listeners += l }
    fun removeCapsListener(l: () -> Unit) { listeners -= l }
    fun notifyCapsChanged() = listeners.forEach { it() }
}
