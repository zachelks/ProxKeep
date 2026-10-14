package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.Credentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TfaChallenge
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.console.ConsoleChannel
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.core.api.console.TermProxyTicket
import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.model.ApiEnvelope
import io.github.zachelks.proxkeep.core.api.model.AgentResult
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.GuestOsInfo
import io.github.zachelks.proxkeep.core.api.model.GuestStatus
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Realm
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
import io.github.zachelks.proxkeep.core.api.model.TaskLogLine
import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.core.api.model.TicketResponse
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import io.github.zachelks.proxkeep.core.api.vnc.VncConnection
import io.github.zachelks.proxkeep.core.api.vnc.VncProxyTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
        call(get(listOf("access", "domains"), authenticated = false), ListSerializer(Realm.serializer()))

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
        val raw = execute(post(listOf("access", "ticket"), form, authenticated = false))
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

    // --- Access ----------------------------------------------------------

    /**
     * What the credentials in use may do: the signed-in user's permissions,
     * or an API token's own.
     *
     * @param path limits the answer to one path, for which the server then
     *   works out every source of permissions, such as a guest's pool
     */
    suspend fun permissions(path: String? = null): Permissions {
        val query = path?.let { mapOf("path" to it) }.orEmpty()
        return Permissions.fromJson(call(get(listOf("access", "permissions"), query = query), JsonObject.serializer()))
    }

    // --- Cluster ---------------------------------------------------------

    suspend fun version(): VersionInfo =
        call(get(listOf("version")), VersionInfo.serializer())

    /** Every node, guest and storage in the cluster, in one call. */
    suspend fun clusterResources(): List<ClusterResource> =
        call(get(listOf("cluster", "resources")), ListSerializer(ClusterResource.serializer()))

    // --- Guests ----------------------------------------------------------

    suspend fun guestStatus(node: String, kind: ResourceKind, vmid: Int): GuestStatus =
        call(get(guestPath(node, kind, vmid) + listOf("status", "current")), GuestStatus.serializer())

    suspend fun guestConfig(node: String, kind: ResourceKind, vmid: Int): GuestConfig =
        GuestConfig.fromJson(call(get(guestPath(node, kind, vmid) + "config"), JsonObject.serializer()))

    /**
     * Asks the QEMU guest agent inside a virtual machine which operating
     * system it runs.
     *
     * @throws PveApiException when the agent is not installed or not running
     */
    suspend fun guestOsInfo(node: String, vmid: Int): GuestOsInfo {
        val path = guestPath(node, ResourceKind.Qemu, vmid) + listOf("agent", "get-osinfo")
        return call(get(path), AgentResult.serializer(GuestOsInfo.serializer())).result
            ?: throw PveApiException(STATUS_OK, "The guest agent returned no data")
    }

    /**
     * Starts a power action. The server queues it as a task and answers at
     * once; use [awaitTask] with the returned task ID to learn how it went.
     *
     * @return the task's UPID
     */
    suspend fun guestAction(node: String, kind: ResourceKind, vmid: Int, action: GuestAction): String {
        require(action.supports(kind)) { "${action.name} is not available for $kind guests" }
        val path = guestPath(node, kind, vmid) + listOf("status", action.apiName)
        return call(post(path, emptyMap()), String.serializer())
    }

    /** A guest's usage history over [timeframe], oldest sample first. */
    suspend fun guestRrd(node: String, kind: ResourceKind, vmid: Int, timeframe: RrdTimeframe): List<RrdPoint> =
        rrd(guestPath(node, kind, vmid), timeframe)

    /** A node's usage history over [timeframe], oldest sample first. */
    suspend fun nodeRrd(node: String, timeframe: RrdTimeframe): List<RrdPoint> =
        rrd(listOf("nodes", node), timeframe)

    private suspend fun rrd(base: List<String>, timeframe: RrdTimeframe): List<RrdPoint> {
        val query = mapOf("timeframe" to timeframe.apiName, "cf" to "AVERAGE")
        return call(get(base + "rrddata", query = query), ListSerializer(JsonObject.serializer()))
            .mapNotNull(RrdPoint::fromJson)
            .sortedBy { it.time }
    }

    private fun guestPath(node: String, kind: ResourceKind, vmid: Int): List<String> {
        val type = when (kind) {
            ResourceKind.Qemu -> "qemu"
            ResourceKind.Lxc -> "lxc"
            else -> throw IllegalArgumentException("$kind is not a guest")
        }
        return listOf("nodes", node, type, vmid.toString())
    }

    // --- Consoles --------------------------------------------------------

    /**
     * Opens a text console: asks the server to start a terminal process, then
     * connects to it. Returns as soon as the connection is under way;
     * [listener] learns whether it succeeded.
     *
     * @param scope runs the channel's keep-alive; cancelling it does not close the channel
     * @throws PveApiException when the server refuses to start the terminal
     */
    suspend fun openConsole(
        target: ConsoleTarget,
        scope: CoroutineScope,
        listener: ConsoleChannel.Listener,
    ): ConsoleChannel {
        val base = when (target) {
            is ConsoleTarget.NodeShell -> listOf("nodes", target.node)
            is ConsoleTarget.Guest -> guestPath(target.node, target.kind, target.vmid)
        }
        val ticket = call(post(base + "termproxy", emptyMap()), TermProxyTicket.serializer())
        // The websocket is authenticated twice: by the session's credentials on
        // the request, and by the one-time ticket once it is open.
        val query = mapOf("port" to ticket.port.toString(), "vncticket" to ticket.ticket)
        val request = builder(base + "vncwebsocket", query, authenticated = true)
            .header("Sec-WebSocket-Protocol", "binary")
            .get()
            .build()
        return ConsoleChannel.connect(http, request, ticket, scope, listener)
    }

    /**
     * Opens a virtual machine's display: asks the server for a VNC proxy,
     * then connects to it through the server's websocket relay. Returns as
     * soon as the connection is under way; [listener] learns how it goes.
     *
     * @throws PveApiException when the server refuses to start the proxy
     */
    suspend fun openDesktop(node: String, vmid: Int, listener: VncConnection.Listener): VncConnection {
        val base = guestPath(node, ResourceKind.Qemu, vmid)
        // A generated password is stronger than the ticket, of which VNC uses only 8 characters.
        val form = mapOf("websocket" to "1", "generate-password" to "1")
        val ticket = call(post(base + "vncproxy", form), VncProxyTicket.serializer())
        val query = mapOf("port" to ticket.port.toString(), "vncticket" to ticket.ticket)
        val request = builder(base + "vncwebsocket", query, authenticated = true)
            .header("Sec-WebSocket-Protocol", "binary")
            .get()
            .build()
        return VncConnection.connect(http, request, ticket.password ?: ticket.ticket, listener)
    }

    // --- Tasks -----------------------------------------------------------

    /** Recent and running tasks across the cluster, newest first. */
    suspend fun clusterTasks(): List<TaskSummary> =
        call(get(listOf("cluster", "tasks")), ListSerializer(TaskSummary.serializer()))

    suspend fun taskStatus(node: String, upid: String): TaskStatus =
        call(get(listOf("nodes", node, "tasks", upid, "status")), TaskStatus.serializer())

    /**
     * Reads part of a task's log.
     *
     * @param start how many lines to skip from the top
     * @param limit the most lines to return
     */
    suspend fun taskLog(node: String, upid: String, start: Int = 0, limit: Int = 500): List<TaskLogLine> {
        val query = mapOf("start" to start.toString(), "limit" to limit.toString())
        return call(get(listOf("nodes", node, "tasks", upid, "log"), query = query), ListSerializer(TaskLogLine.serializer()))
    }

    // --- Plumbing --------------------------------------------------------

    /** [path] holds one entry per path segment, so values such as task IDs are escaped as a whole. */
    private fun builder(path: List<String>, query: Map<String, String>, authenticated: Boolean): Request.Builder {
        val url = baseUrl.newBuilder()
        path.forEach { url.addPathSegment(it) }
        query.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val request = Request.Builder()
            .url(url.build())
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

    private fun get(
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): Request = builder(path, query, authenticated).get().build()

    private fun post(path: List<String>, form: Map<String, String>, authenticated: Boolean = true): Request {
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = builder(path, emptyMap(), authenticated).post(body)
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
