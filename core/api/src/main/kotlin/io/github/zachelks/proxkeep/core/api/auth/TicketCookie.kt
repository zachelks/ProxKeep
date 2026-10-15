package io.github.zachelks.proxkeep.core.api.auth

import java.net.URLDecoder

/**
 * Reads a login ticket from the cookie a product keeps it in, such as
 * `PVEAuthCookie`. Newer servers name the cookie with the `__Host-` prefix,
 * so both spellings are read.
 */
object TicketCookie {
    private const val HOST_PREFIX = "__Host-"

    /** Whether a cookie called [name] holds the ticket that [cookieName] names. */
    private fun matches(name: String, cookieName: String): Boolean = name == cookieName || name == HOST_PREFIX + cookieName

    /**
     * The ticket in a `Cookie` header value, such as `lang=en; PVEAuthCookie=…`,
     * or null when there is none. Both spellings can be present at once, one
     * of them stale, so the first value that [usable] accepts is taken.
     */
    fun fromCookieHeader(header: String?, cookieName: String, usable: (String) -> Boolean = { true }): String? =
        header?.split(';')?.firstNotNullOfOrNull { pair -> valueOf(pair, cookieName)?.takeIf(usable) }

    /** The ticket in `Set-Cookie` header values, or null when there is none. */
    fun fromSetCookies(headers: List<String>, cookieName: String, usable: (String) -> Boolean = { true }): String? =
        headers.firstNotNullOfOrNull { header -> valueOf(header.substringBefore(';'), cookieName)?.takeIf(usable) }

    private fun valueOf(pair: String, cookieName: String): String? {
        val trimmed = pair.trim()
        if (!matches(trimmed.substringBefore('='), cookieName)) return null
        val value = trimmed.substringAfter('=', missingDelimiterValue = "")
        if (value.isEmpty()) return null
        // Tickets end in base64, whose `+` must survive decoding.
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (ignored: IllegalArgumentException) {
            value
        }
    }
}
