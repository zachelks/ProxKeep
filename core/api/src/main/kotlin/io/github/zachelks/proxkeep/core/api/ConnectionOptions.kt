package io.github.zachelks.proxkeep.core.api

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.net.ssl.X509KeyManager

/**
 * What a reverse proxy in front of a server may need besides its address:
 * extra HTTP headers, such as an access token, and a client certificate.
 *
 * @property headers sent with every request, including the console
 *   websocket. A header the request already carries, such as the session's
 *   own credentials, is left as it is.
 * @property clientKey presents a client certificate when the server asks for one
 * @throws IllegalArgumentException when a header name or value is not valid HTTP
 */
class ConnectionOptions(
    val headers: List<Pair<String, String>> = emptyList(),
    val clientKey: X509KeyManager? = null,
) {
    init {
        // Fails early, with the offending header named, rather than on the first request.
        Headers.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }
    }

    /** Returns a copy of [client] that sends [headers]. The client certificate is applied by `ServerTrust`. */
    fun applyTo(client: OkHttpClient): OkHttpClient {
        if (headers.isEmpty()) return client
        return client.newBuilder().addInterceptor(HeaderInterceptor(headers)).build()
    }

    private class HeaderInterceptor(private val headers: List<Pair<String, String>>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val builder = request.newBuilder()
            headers.forEach { (name, value) -> if (request.header(name) == null) builder.addHeader(name, value) }
            return chain.proceed(builder.build())
        }
    }

    companion object {
        /** Whether [name] can be sent as a header name. */
        fun isValidName(name: String): Boolean = name.isNotEmpty() && name.all { it in TOKEN_CHARS }

        /** Whether [value] can be sent as a header value. */
        fun isValidValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

        private val TOKEN_CHARS: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()
    }
}
