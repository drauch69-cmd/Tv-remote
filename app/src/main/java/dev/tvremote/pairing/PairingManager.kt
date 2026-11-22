package dev.tvremote.pairing

import android.os.SystemClock
import dev.tvremote.data.Prefs
import java.security.SecureRandom

/**
 * First-time pairing: the phone asks for a code, the TV shows a 4-digit code on
 * screen, and the phone sends it back to get a long-lived token.
 *
 * Limits: a code lasts [CODE_TTL_MS]; after [MAX_ATTEMPTS] wrong guesses
 * pairing is locked for [LOCK_MS].
 */
class PairingManager(private val prefs: Prefs) {

    sealed class Result {
        data class Ok(val token: String) : Result()
        data class WrongCode(val attemptsLeft: Int) : Result()
        object Expired : Result()
        object Locked : Result()
    }

    private val random = SecureRandom()
    private var code: String? = null
    private var expiresAt = 0L
    private var attemptsLeft = MAX_ATTEMPTS
    private var lockedUntil = 0L

    /** Called with the code to show, or null to hide it. Set by the server. */
    @Volatile
    var onCodeChanged: ((String?) -> Unit)? = null

    /** The code currently on screen (the settings screen shows it too), or null. */
    @get:Synchronized
    val activeCode: String?
        get() = code?.takeIf { now() < expiresAt }

    fun isValidToken(token: String?) = !token.isNullOrBlank() && token in prefs.tokens

    /** Starts (or restarts) pairing. Returns seconds until the code expires, or -1 if locked. */
    @Synchronized
    fun requestCode(): Int {
        if (now() < lockedUntil) return -1
        // Re-use a live code so two taps don't make the code on screen go stale.
        if (activeCode == null) {
            code = "%04d".format(random.nextInt(10_000))
            attemptsLeft = MAX_ATTEMPTS
        }
        expiresAt = now() + CODE_TTL_MS
        onCodeChanged?.invoke(code)
        return (CODE_TTL_MS / 1000).toInt()
    }

    @Synchronized
    fun verify(input: String): Result {
        if (now() < lockedUntil) return Result.Locked
        val current = activeCode ?: return Result.Expired
        if (input.trim() == current) {
            val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            prefs.addToken(token)
            clear()
            return Result.Ok(token)
        }
        attemptsLeft--
        if (attemptsLeft <= 0) {
            lockedUntil = now() + LOCK_MS
            clear()
            return Result.Locked
        }
        return Result.WrongCode(attemptsLeft)
    }

    /** Hides the code (after success, lockout, or expiry). */
    @Synchronized
    fun clear() {
        code = null
        onCodeChanged?.invoke(null)
    }

    fun forgetAllPhones() = prefs.clearTokens()

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val CODE_TTL_MS = 120_000L
        const val MAX_ATTEMPTS = 5
        const val LOCK_MS = 60_000L
    }
}
