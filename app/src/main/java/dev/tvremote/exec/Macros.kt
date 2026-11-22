package dev.tvremote.exec

import android.util.Log
import dev.tvremote.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Saved command sequences ("macros"), stored on the TV so both the phone page
 * and iOS Shortcuts (via the HTTP API) can run them by name.
 */
class MacroStore(private val prefs: Prefs) {

    /** All macros, in saved order. */
    @Synchronized
    fun all(): JSONArray = try { JSONArray(prefs.macrosJson) } catch (_: Exception) { JSONArray() }

    @Synchronized
    fun find(name: String): JSONObject? {
        val list = all()
        return (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
    }

    /** Saves [macro] (replacing one with the same name, or [oldName] when renaming). */
    @Synchronized
    fun save(macro: JSONObject, oldName: String?) {
        val name = macro.optString("name").trim().take(40)
        require(name.isNotEmpty()) { "Macro needs a name" }
        val steps = macro.optJSONArray("steps") ?: JSONArray()
        require(steps.length() in 1..MAX_STEPS) { "A macro needs 1–$MAX_STEPS steps" }
        val clean = JSONObject().put("name", name).put("steps", steps)

        val out = JSONArray()
        var replaced = false
        val list = all()
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            val n = m.optString("name")
            when {
                n == name || n == oldName -> if (!replaced) { out.put(clean); replaced = true }
                else -> out.put(m)
            }
        }
        if (!replaced) out.put(clean)
        prefs.macrosJson = out.toString()
        changed()
    }

    @Synchronized
    fun delete(name: String) {
        val list = all()
        val out = JSONArray()
        for (i in 0 until list.length()) list.getJSONObject(i).let { if (it.optString("name") != name) out.put(it) }
        prefs.macrosJson = out.toString()
        changed()
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { it() }

    companion object { const val MAX_STEPS = 50 }
}

/**
 * Runs a macro: `{"c":"MACRO","name":"..."}`. Steps run one after another on a
 * background thread (so the phone isn't kept waiting), with a short gap so the
 * TV can keep up. Failing steps are skipped.
 */
class MacroExecutor(private val store: MacroStore, private val routerProvider: () -> CommandRouter) : CommandExecutor {
    override val id = "macros"
    override fun isAvailable() = true
    override fun supports(name: String) = name == "MACRO"

    private val runner = Executors.newSingleThreadExecutor()

    override fun execute(cmd: Command): Boolean {
        val name = cmd.args.optString("name")
        val macro = store.find(name) ?: throw ExecError("No macro named “$name”")
        val steps = macro.optJSONArray("steps") ?: return true
        runner.execute { run(name, steps) }
        return true
    }

    private fun run(name: String, steps: JSONArray) {
        val router = routerProvider()
        for (i in 0 until minOf(steps.length(), MacroStore.MAX_STEPS)) {
            val step = steps.optJSONObject(i) ?: continue
            when (val c = step.optString("c")) {
                "WAIT" -> Thread.sleep(step.optLong("ms", 1000).coerceIn(0, 30_000))
                "MACRO", "" -> continue // no nesting
                else -> {
                    router.run(Command(c, step))?.let { Log.w(TAG, "Macro '$name' step $i ($c): $it") }
                    Thread.sleep(GAP_MS)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Macros"
        const val GAP_MS = 300L
    }
}
