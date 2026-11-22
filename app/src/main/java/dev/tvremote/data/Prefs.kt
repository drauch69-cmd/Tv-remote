package dev.tvremote.data

import android.content.Context

/** Small wrapper around SharedPreferences for everything the app persists. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvremote", Context.MODE_PRIVATE)

    /** Tokens handed out to paired phones. */
    @get:Synchronized
    val tokens: Set<String>
        get() = sp.getStringSet(KEY_TOKENS, emptySet())!!.toSet()

    @Synchronized
    fun addToken(token: String) {
        sp.edit().putStringSet(KEY_TOKENS, tokens + token).apply()
    }

    @Synchronized
    fun clearTokens() {
        sp.edit().remove(KEY_TOKENS).apply()
    }

    /** Saved macros, as a JSON array string. */
    @get:Synchronized @set:Synchronized
    var macrosJson: String
        get() = sp.getString(KEY_MACROS, "[]")!!
        set(value) { sp.edit().putString(KEY_MACROS, value).apply() }

    private companion object {
        const val KEY_MACROS = "macros"
        const val KEY_TOKENS = "tokens"
    }
}
