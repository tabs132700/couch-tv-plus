package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Phone pairing. The TV shows a 6 digit code; a phone that types it gets a long random token, and
 * every API call must carry that token. The TV keeps only a SHA-256 of each token.
 *
 * Limits: a code lasts 3 minutes, and 5 wrong tries throw it away (a new one has to be shown on the
 * TV). So guessing is about 5 in a million per code someone can see on the screen.
 */
object Auth {
    private val rnd = SecureRandom()
    @Volatile private var pin: String? = null
    @Volatile private var pinUntil = 0L
    @Volatile private var wrong = 0
    private val listeners = mutableListOf<(String?) -> Unit>()

    /** Token the TV's own home page uses (set as a cookie in its WebView). Never shown anywhere. */
    val internalToken: String by lazy {
        Store.str("internal_token") ?: hex(32).also { Store.putStr("internal_token", it) }
    }

    @Synchronized fun newPin(): String {
        val p = String.format("%06d", rnd.nextInt(1_000_000))
        pin = p; pinUntil = System.currentTimeMillis() + 3 * 60_000; wrong = 0
        notifyPin()
        return p
    }

    /** The code to show on the TV now, or null if none is live. */
    fun currentPin(): String? = if (pin != null && System.currentTimeMillis() < pinUntil) pin else null

    fun onPin(fn: (String?) -> Unit) { synchronized(listeners) { listeners += fn } }
    fun offPin(fn: (String?) -> Unit) { synchronized(listeners) { listeners -= fn } }
    private fun notifyPin() { val p = currentPin(); synchronized(listeners) { listeners.toList() }.forEach { it(p) } }

    // the QR on the TV pairs in one scan. It carries a long random link code (not the 6 digits),
    // good for 10 minutes and for every phone that scans it then, so a family can all scan the same QR.
    @Volatile private var link: String? = null
    @Volatile private var linkUntil = 0L

    /** The link code for the QR on screen now: the same one while it has over 2 minutes left, else a new one. */
    @Synchronized fun pairLink(): String {
        val now = System.currentTimeMillis()
        link?.let { if (now < linkUntil - 2 * 60_000) return it }
        return hex(10).also { link = it; linkUntil = now + 10 * 60_000 }
    }

    /** Returns a new token for the right code, null for a wrong one. */
    @Synchronized fun pair(code: String?, name: String?): String? {
        val l = link
        if (l != null && System.currentTimeMillis() < linkUntil && code != null && code.length == l.length &&
            MessageDigest.isEqual(code.lowercase().toByteArray(), l.toByteArray())) return issue(name)
        val live = currentPin() ?: return null
        val given = code?.filter { it.isDigit() }.orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), live.toByteArray())) {
            wrong += 1
            if (wrong >= 5) { pin = null; notifyPin() }
            return null
        }
        pin = null
        notifyPin()
        return issue(name)
    }

    private fun issue(name: String?): String {
        val token = hex(32)
        val list = tokens()
        list.put(JSONObject().put("hash", sha(token)).put("name", (name ?: "Phone").take(40)).put("at", System.currentTimeMillis()))
        Store.putStr("tokens", list.toString())
        return token
    }

    fun valid(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        if (MessageDigest.isEqual(token.toByteArray(), internalToken.toByteArray())) return true
        val h = sha(token)
        val list = tokens()
        for (i in 0 until list.length()) {
            if (MessageDigest.isEqual(list.getJSONObject(i).optString("hash").toByteArray(), h.toByteArray())) return true
        }
        return false
    }

    fun phoneCount() = tokens().length()

    fun forgetAll() = Store.putStr("tokens", "[]")

    private fun tokens(): JSONArray = Store.str("tokens")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun hex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
