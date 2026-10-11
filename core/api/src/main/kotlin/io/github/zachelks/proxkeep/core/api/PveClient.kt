package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.Credentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TfaChallenge
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.model.ApiEnvelope
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.Realm
import io.github.zachelks.proxkeep.core.api.model.TicketResponse
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for one Proxmox VE server.
 *
 * Instances are immutable. Signing in returns credentials, and
 * [withCredentials] returns a client that sends them.
 *
 * Every call throws [PveApiException] when the server answers with an error
 * and another [IOException] when the server cannot be reached or its
 * certificate is not trusted.
 *
 * @param baseUrl root of the JSON API, ending in a slash; see [ServerAddress.apiBaseUrl]
 * @param http a client already configured with the server's trust settings
 */
class PveClient(
    private val baseUrl: HttpUrl,
    private val http: OkHttpClient,
    val credentials: Credentials? = null,
) {
    fun withCredentials(credentials: Credentials?): PveClient = PveClient(baseUrl, http, credentials)

    // --- Sign-in ---------------------------------------------------------

    /** Lists the realms a person can sign in to. Needs no credentials. */
    suspend fun realms(): List<Realm> =
        call(get("access/domains", authenticated = false), ListSerializer(Realm.serializer()))

    /**
     * Signs in with a password.
     *
     * @param username the name without the realm, for example `root`
     * @throws PveApiException with [PveApiException.isAuthenticationFailure] for a wrong name or password
     */
    suspend fun login(username: String, realm: String, password: String): LoginResult {
        val userId = "$username@$realm"
        return requestTicket(userId, mapOf("username" to userId, "password" to password))
    }

    /** Finishes a login that returned [LoginResult.SecondFactorRequired]. */
    suspend fun completeSecondFactor(
        username: String,
        challenge: TfaChallenge,
        kind: SecondFactorKind,
        code: String,
    ): LoginResult.Success {
        val form = mapOf(
            "username" to username,
            "password" to "${kind.apiName}:${code.trim()}",
            "tfa-challenge" to challenge.ticket,
        )
        return requestTicket(username, form) as? LoginResult.Success
            ?: throw PveApiException(STATUS_OK, "The server asked for a second factor again")
    }

    /** Exchanges a ticket that has not expired yet for a fresh one. */
    suspend fun renewTicket(current: TicketCredentials): TicketCredentials {
        val form = mapOf("username" to current.username, "password" to current.ticket)
        val result = requestTicket(current.username, form) as? LoginResult.Success
            ?: throw PveApiException(STATUS_OK, "The server asked for a second factor on renewal")
        return result.credentials
    }

    private suspend fun requestTicket(userId: String, form: Map<String, String>): LoginResult {
        val raw = execute(post("access/ticket", form, authenticated = false))
        val response = decode(raw, TicketResponse.serializer())
        val ticket = response.ticket
            ?: ticketFromCookies(raw.setCookies)
            ?: throw PveApiException(raw.code, "The server did not return a ticket")
        val name = response.username ?: userId
        val challenge = TfaChallenge.fromTicket(ticket)
        return if (challenge != null) {
            LoginResult.SecondFactorRequired(name, challenge)
        } else {
            LoginResult.Success(TicketCredentials(name, ticket, response.csrfToken), response.clustername)
        }
    }

    // --- Read calls ------------------------------------------------------

    suspend fun version(): VersionInfo =
        call(get("version"), VersionInfo.serializer())

    /** Every node, guest and storage in the cluster, in one call. */
    suspend fun clusterResources(): List<ClusterResource> =
        call(get("cluster/resources"), ListSerializer(ClusterResource.serializer()))

    // --- Plumbing --------------------------------------------------------

    private fun builder(path: String, authenticated: Boolean): Request.Builder {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json")
        if (authenticated) {
            when (val current = credentials) {
                is TicketCredentials -> request.header("Cookie", "$TICKET_COOKIE=${current.ticket}")
                is ApiTokenCredentials -> request.header("Authorization", "PVEAPIToken=${current.tokenId}=${current.secret}")
                null -> Unit
            }
        }
        return request
    }

    private fun get(path: String, authenticated: Boolean = true): Request =
        builder(path, authenticated).get().build()

    private fun post(path: String, form: Map<String, String>, authenticated: Boolean = true): Request {
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = builder(path, authenticated).post(body)
        // Tickets must prove write requests were not forged; API tokens need no such proof.
        val csrfToken = (credentials as? TicketCredentials)?.csrfToken
        if (authenticated && csrfToken != null) request.header("CSRFPreventionToken", csrfToken)
        return request.build()
    }

    private suspend fun <T> call(request: Request, deserializer: DeserializationStrategy<T>): T =
        decode(execute(request), deserializer)

    private suspend fun execute(request: Request): RawResponse = http.newCall(request).await()

    private fun <T> decode(raw: RawResponse, deserializer: DeserializationStrategy<T>): T {
        val envelope = try {
            PveJson.decodeFromString(ENVELOPE, raw.body)
        } catch (ignored: SerializationException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }
        if (raw.code !in 200..299) {
            // The server describes most failures in the HTTP status line.
            val message = envelope?.message?.takeIf { it.isNotBlank() } ?: raw.message
            throw PveApiException(raw.code, message.ifBlank { "Request failed" }, envelope?.errors.orEmpty())
        }
        val data = envelope?.data
            ?: throw PveApiException(raw.code, "The server sent an empty or unreadable response")
        return try {
            PveJson.decodeFromJsonElement(deserializer, data)
        } catch (e: SerializationException) {
            throw PveApiException(raw.code, "The server sent a response in an unexpected format", cause = e)
        } catch (e: IllegalArgumentException) {
            throw PveApiException(raw.code, "The server sent a response in an unexpected format", cause = e)
        }
    }

    private class RawResponse(
        val code: Int,
        val message: String,
        val body: String,
        val setCookies: List<String>,
    )

    private suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use {
                        RawResponse(it.code, it.message, it.body.string(), it.headers("Set-Cookie"))
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(raw)
            }
        })
    }

    private companion object {
        const val STATUS_OK = 200
        const val TICKET_COOKIE = "PVEAuthCookie"
        val ENVELOPE = ApiEnvelope.serializer(JsonElement.serializer())

        /** Fallback for servers that deliver the ticket only as a cookie. */
        fun ticketFromCookies(setCookies: List<String>): String? {
            for (header in setCookies) {
                val pair = header.substringBefore(';').trim()
                val name = pair.substringBefore('=')
                if (name == TICKET_COOKIE || name == "__Host-$TICKET_COOKIE") {
                    val value = pair.substringAfter('=', missingDelimiterValue = "")
                    if (value.isEmpty()) continue
                    return try {
                        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
                    } catch (ignored: IllegalArgumentException) {
                        value
                    }
                }
            }
            return null
        }
    }
}
