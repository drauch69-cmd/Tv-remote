package dev.tvremote.exec

import org.json.JSONObject

/** One command from the phone, e.g. name = "DPAD_UP". Extra fields stay in [args]. */
data class Command(val name: String, val args: JSONObject = JSONObject())

/**
 * A way of controlling the TV (Accessibility, MediaSession, Shizuku, ...).
 * The [CommandRouter] picks the first executor that is available and supports a command.
 */
interface CommandExecutor {
    /** Short id used in the capabilities sent to the phone, e.g. "accessibility". */
    val id: String

    /** True if this backend can be used right now (permission granted, service running...). */
    fun isAvailable(): Boolean

    /** True if this backend knows how to run [name]. */
    fun supports(name: String): Boolean

    /** Runs the command. Returns false if it failed. */
    fun execute(cmd: Command): Boolean
}
