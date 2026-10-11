package io.github.zachelks.proxkeep.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Host and port of a Proxmox VE server. The API is always served over HTTPS. */
data class ServerAddress(
    val host: String,
    val port: Int = DEFAULT_PORT,
) {
    /** Root of the JSON API, ending in a slash so paths can be appended. */
    val apiBaseUrl: HttpUrl
        get() = HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .port(port)
            .addPathSegments("api2/json/")
            .build()

    /** Short form for display, for example `pve.example.com:8006`. */
    val display: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        const val DEFAULT_PORT: Int = 8006

        private val explicitPort = Regex(""":\d{1,5}$""")

        /**
         * Parses what a person types into a server field: a host name or IP
         * address, optionally with a port, a scheme or a trailing path.
         * Returns null when the text is not a usable address.
         */
        fun parse(input: String): ServerAddress? {
            var text = input.trim()
            val schemeEnd = text.indexOf("://")
            if (schemeEnd >= 0) text = text.substring(schemeEnd + 3)
            val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty() || authority.contains('@') || authority.contains(' ')) return null
            val url = "https://$authority/".toHttpUrlOrNull() ?: return null
            val port = if (explicitPort.containsMatchIn(authority)) url.port else DEFAULT_PORT
            return ServerAddress(url.host, port)
        }
    }
}
