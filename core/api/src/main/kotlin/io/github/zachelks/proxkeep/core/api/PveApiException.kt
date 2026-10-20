package io.github.zachelks.proxkeep.core.api

import java.io.IOException

/**
 * The server answered, but with an error or with something that could not be
 * understood. Network and TLS failures surface as other [IOException] types.
 */
class PveApiException(
    /** HTTP status code. */
    val statusCode: Int,
    /** The server's own description of the problem. */
    val statusMessage: String,
    /** Rejected request parameters, keyed by parameter name. */
    val parameterErrors: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : IOException(describe(statusCode, statusMessage, parameterErrors), cause) {

    val isAuthenticationFailure: Boolean
        get() = statusCode == 401

    val isPermissionDenied: Boolean
        get() = statusCode == 403

    private companion object {
        fun describe(code: Int, message: String, errors: Map<String, String>): String {
            val details = errors.entries.joinToString("; ") { "${it.key}: ${it.value.trim()}" }
            val base = "$code ${message.trim()}".trim()
            return if (details.isEmpty()) base else "$base ($details)"
        }
    }
}

/**
 * The server does not publish its API schema, for example because the
 * documentation package is not installed. This is not a refusal of the
 * session's credentials, whatever the status code.
 */
class SchemaUnavailableException(val statusCode: Int) : IOException("The server does not publish its API schema ($statusCode)")
