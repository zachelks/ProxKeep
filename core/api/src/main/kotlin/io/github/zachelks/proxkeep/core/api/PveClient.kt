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
import io.github.zachelks.proxkeep.core.api.model.AgentResult
import io.github.zachelks.proxkeep.core.api.model.ApiEnvelope
import io.github.zachelks.proxkeep.core.api.model.ApplianceTemplate
import io.github.zachelks.proxkeep.core.api.model.BackupCompression
import io.github.zachelks.proxkeep.core.api.model.BackupJob
import io.github.zachelks.proxkeep.core.api.model.CephStatus
import io.github.zachelks.proxkeep.core.api.model.BackupMode
import io.github.zachelks.proxkeep.core.api.model.ChecksumAlgorithm
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.GuestOsInfo
import io.github.zachelks.proxkeep.core.api.model.GuestStatus
import io.github.zachelks.proxkeep.core.api.model.MigrationCheck
import io.github.zachelks.proxkeep.core.api.model.NodeCertificate
import io.github.zachelks.proxkeep.core.api.model.NodeDns
import io.github.zachelks.proxkeep.core.api.model.NodeNetwork
import io.github.zachelks.proxkeep.core.api.model.NodeService
import io.github.zachelks.proxkeep.core.api.model.NodeStatus
import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.NodeTime
import io.github.zachelks.proxkeep.core.api.model.PackageUpdate
import io.github.zachelks.proxkeep.core.api.model.PendingChange
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.PhysicalDisk
import io.github.zachelks.proxkeep.core.api.model.Realm
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.RrdPoint
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
import io.github.zachelks.proxkeep.core.api.model.ServiceAction
import io.github.zachelks.proxkeep.core.api.model.SmartData
import io.github.zachelks.proxkeep.core.api.model.Snapshot
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import io.github.zachelks.proxkeep.core.api.model.TaskLogLine
import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.core.api.model.TicketResponse
import io.github.zachelks.proxkeep.core.api.model.UploadProgress
import io.github.zachelks.proxkeep.core.api.model.UrlMetadata
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
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
    val product: Product = Product.Pve,
) {
    fun withCredentials(credentials: Credentials?): PveClient = PveClient(baseUrl, http, credentials, product)

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
            ?: ticketFromCookies(raw.setCookies, product.cookieName)
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

    // --- Configuration ---------------------------------------------------

    /** Changes that wait for the guest's next start, and the current values beside them. */
    suspend fun pendingConfig(node: String, kind: ResourceKind, vmid: Int): List<PendingChange> =
        call(get(guestPath(node, kind, vmid) + "pending"), ListSerializer(JsonObject.serializer()))
            .mapNotNull(PendingChange::fromJson)

    /**
     * Changes a guest's configuration. Keys in [delete] are removed, which
     * for a disk detaches it. With [digest] from [guestConfig], the server
     * refuses the change when someone else changed the configuration since.
     *
     * Virtual machines apply changes as a task, because adding a disk
     * allocates storage; containers apply them at once.
     *
     * @return the task's UPID, or null when the change is already done
     */
    suspend fun updateConfig(
        node: String,
        kind: ResourceKind,
        vmid: Int,
        set: Map<String, String>,
        delete: Collection<String> = emptyList(),
        digest: String? = null,
    ): String? {
        val form = LinkedHashMap(set)
        if (delete.isNotEmpty()) form["delete"] = delete.joinToString(",")
        digest?.let { form["digest"] = it }
        val path = guestPath(node, kind, vmid) + "config"
        return callForTask(if (kind == ResourceKind.Qemu) post(path, form) else put(path, form))
    }

    /**
     * Grows a disk. Disks cannot shrink.
     *
     * @param size the new size, such as `64G`, or an increase, such as `+8G`
     */
    suspend fun resizeDisk(node: String, kind: ResourceKind, vmid: Int, disk: String, size: String): String? =
        callForTask(put(guestPath(node, kind, vmid) + "resize", mapOf("disk" to disk, "size" to size)))

    /** Writes a virtual machine's cloud-init drive again with its current settings. */
    suspend fun regenerateCloudInit(node: String, vmid: Int) {
        callForTask(put(guestPath(node, ResourceKind.Qemu, vmid) + "cloudinit", emptyMap()))
    }

    /** A node's network interfaces; [type] narrows them, for example to `any_bridge`. */
    suspend fun nodeNetworks(node: String, type: String? = null): List<NodeNetwork> {
        val query = type?.let { mapOf("type" to it) }.orEmpty()
        return call(get(listOf("nodes", node, "network"), query = query), ListSerializer(NodeNetwork.serializer()))
            .sortedBy { it.iface }
    }

    /** Creates a virtual machine from the parameters of `POST /nodes/{node}/qemu`. Returns the task's UPID. */
    suspend fun createVm(node: String, parameters: Map<String, String>): String =
        callForTask(post(listOf("nodes", node, "qemu"), parameters))
            ?: throw PveApiException(STATUS_OK, "The server did not start a task")

    /** Creates a container from the parameters of `POST /nodes/{node}/lxc`. Returns the task's UPID. */
    suspend fun createContainer(node: String, parameters: Map<String, String>): String =
        callForTask(post(listOf("nodes", node, "lxc"), parameters))
            ?: throw PveApiException(STATUS_OK, "The server did not start a task")

    /**
     * The API viewer's data file, which describes every path and parameter
     * of this server's API. Parse it with [ApiSchema.parse].
     *
     * @throws SchemaUnavailableException when the server does not publish it
     */
    suspend fun apiSchemaText(): String {
        val url = baseUrl.resolve(product.schemaPath) ?: throw SchemaUnavailableException(0)
        val request = builder(emptyList(), emptyMap(), authenticated = true).url(url).header("Accept", "*/*").get().build()
        val raw = execute(request)
        if (raw.code !in 200..299) throw SchemaUnavailableException(raw.code)
        return raw.body
    }

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

    // --- Nodes -----------------------------------------------------------

    suspend fun nodeStatus(node: String): NodeStatus = call(get(listOf("nodes", node, "status")), NodeStatus.serializer())

    /** Reboots or shuts down a node. Guests on it are shut down first, as configured. */
    suspend fun nodePower(node: String, reboot: Boolean): String? =
        callForTask(post(listOf("nodes", node, "status"), mapOf("command" to if (reboot) "reboot" else "shutdown")))

    /** Packages with a newer version in the node's package lists, as last refreshed. */
    suspend fun packageUpdates(node: String): List<PackageUpdate> =
        call(get(listOf("nodes", node, "apt", "update")), ListSerializer(PackageUpdate.serializer())).sortedBy { it.name }

    /** Downloads the node's package lists again, as `apt update` does. Returns the task's UPID. */
    suspend fun refreshPackageLists(node: String): String? = callForTask(post(listOf("nodes", node, "apt", "update"), emptyMap()))

    suspend fun services(node: String): List<NodeService> =
        call(get(listOf("nodes", node, "services")), ListSerializer(NodeService.serializer())).sortedBy { it.service }

    suspend fun serviceAction(node: String, service: String, action: ServiceAction): String? =
        callForTask(post(listOf("nodes", node, "services", service, action.apiName), emptyMap()))

    /** Applies the node's pending network configuration. Returns the task's UPID. */
    suspend fun applyNetwork(node: String): String? = callForTask(put(listOf("nodes", node, "network"), emptyMap()))

    /** Throws away the node's pending network configuration. */
    suspend fun revertNetwork(node: String) {
        callForTask(delete(listOf("nodes", node, "network")))
    }

    suspend fun nodeDns(node: String): NodeDns = call(get(listOf("nodes", node, "dns")), NodeDns.serializer())

    /** Sets the node's DNS search domain and up to three servers. */
    suspend fun setNodeDns(node: String, search: String, servers: List<String>) {
        val form = LinkedHashMap<String, String>()
        form["search"] = search
        servers.take(MAX_DNS_SERVERS).forEachIndexed { index, server -> form["dns${index + 1}"] = server }
        val missing = (servers.size until MAX_DNS_SERVERS).map { "dns${it + 1}" }
        if (missing.isNotEmpty()) form["delete"] = missing.joinToString(",")
        callForTask(put(listOf("nodes", node, "dns"), form))
    }

    suspend fun nodeTime(node: String): NodeTime = call(get(listOf("nodes", node, "time")), NodeTime.serializer())

    suspend fun setTimezone(node: String, timezone: String) {
        callForTask(put(listOf("nodes", node, "time"), mapOf("timezone" to timezone)))
    }

    suspend fun physicalDisks(node: String): List<PhysicalDisk> =
        call(get(listOf("nodes", node, "disks", "list")), ListSerializer(PhysicalDisk.serializer())).sortedBy { it.devpath }

    suspend fun smart(node: String, disk: String): SmartData =
        call(get(listOf("nodes", node, "disks", "smart"), query = mapOf("disk" to disk)), SmartData.serializer())

    suspend fun certificates(node: String): List<NodeCertificate> =
        call(get(listOf("nodes", node, "certificates", "info")), ListSerializer(NodeCertificate.serializer()))

    /** The newest [lastEntries] lines of the node's system journal, oldest first. */
    suspend fun journal(node: String, lastEntries: Int = 500): List<String> =
        call(get(listOf("nodes", node, "journal"), query = mapOf("lastentries" to lastEntries.toString())), ListSerializer(String.serializer()))

    // --- Storage ---------------------------------------------------------

    suspend fun storageStatus(node: String, storage: String): NodeStorage {
        val json = call(get(listOf("nodes", node, "storage", storage, "status")), JsonObject.serializer())
        // The status leaves out the storage's own name.
        return PveJson.decodeFromJsonElement(NodeStorage.serializer(), JsonObject(json + ("storage" to JsonPrimitive(storage))))
    }

    /** Deletes a volume, such as an ISO image or a backup. Returns the task's UPID when the deletion runs as one. */
    suspend fun deleteVolume(node: String, storage: String, volid: String): String? =
        callForTask(delete(listOf("nodes", node, "storage", storage, "content", volid)))

    /**
     * Uploads a file to a storage: an ISO image, a container template or a
     * disk image to import. The server copies it into place as a task.
     *
     * @param content `iso`, `vztmpl` or `import`
     * @param open opens the file's contents; it is read once, as it is sent
     * @return the task's UPID
     */
    suspend fun upload(
        node: String,
        storage: String,
        content: String,
        fileName: String,
        length: Long,
        open: () -> InputStream,
        progress: UploadProgress = UploadProgress { _, _ -> },
    ): String? {
        val file = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength() = length

            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(UPLOAD_CHUNK)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                        progress.onProgress(sent, length)
                    }
                }
            }
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", content)
            .addFormDataPart("filename", fileName, file)
            .build()
        val request = builder(listOf("nodes", node, "storage", storage, "upload"), emptyMap(), authenticated = true).post(body)
        (credentials as? TicketCredentials)?.csrfToken?.let { request.header("CSRFPreventionToken", it) }
        // Large files take longer than any read timeout to send.
        val call = http.newBuilder().writeTimeout(0, TimeUnit.MILLISECONDS).readTimeout(UPLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
            .newCall(request.build())
        return taskFrom(call.await())
    }

    /** What a download from [url] would fetch, so its name and size can be shown first. */
    suspend fun queryUrl(node: String, url: String, verifyCertificates: Boolean = true): UrlMetadata {
        val query = mapOf("url" to url, "verify-certificates" to if (verifyCertificates) "1" else "0")
        return call(get(listOf("nodes", node, "query-url-metadata"), query = query), UrlMetadata.serializer())
    }

    /**
     * Has the node download a file from [url] onto a storage. Returns the task's UPID.
     *
     * @param content `iso`, `vztmpl` or `import`
     */
    suspend fun downloadUrl(
        node: String,
        storage: String,
        content: String,
        url: String,
        fileName: String,
        checksum: Pair<ChecksumAlgorithm, String>? = null,
        verifyCertificates: Boolean = true,
    ): String? {
        val form = linkedMapOf("content" to content, "url" to url, "filename" to fileName, "verify-certificates" to if (verifyCertificates) "1" else "0")
        checksum?.let { (algorithm, value) ->
            form["checksum-algorithm"] = algorithm.apiName
            form["checksum"] = value
        }
        return callForTask(post(listOf("nodes", node, "storage", storage, "download-url"), form))
    }

    /** Container templates in the Proxmox catalog. */
    suspend fun applianceTemplates(node: String): List<ApplianceTemplate> =
        call(get(listOf("nodes", node, "aplinfo")), ListSerializer(ApplianceTemplate.serializer()))

    /** Downloads a catalog template onto a storage. Returns the task's UPID. */
    suspend fun downloadTemplate(node: String, storage: String, template: String): String? =
        callForTask(post(listOf("nodes", node, "aplinfo"), mapOf("storage" to storage, "template" to template)))

    // --- Ceph ------------------------------------------------------------

    suspend fun cephStatus(): CephStatus = CephStatus.fromJson(call(get(listOf("cluster", "ceph", "status")), JsonObject.serializer()))

    // --- Backup jobs -----------------------------------------------------

    /** The datacenter's scheduled backup jobs. */
    suspend fun backupJobs(): List<BackupJob> =
        call(get(listOf("cluster", "backup")), ListSerializer(JsonObject.serializer())).mapNotNull(BackupJob::fromJson).sortedBy { it.id }

    /**
     * Starts a backup on [node] with a job's settings. Returns the task's UPID.
     *
     * @param parameters as from [BackupJob.runParameters]
     */
    suspend fun runBackup(node: String, parameters: Map<String, String>): String? =
        callForTask(post(listOf("nodes", node, "vzdump"), parameters))

    // --- Any path --------------------------------------------------------

    /**
     * Reads any API path, such as `/cluster/options`, for screens built from
     * the API schema.
     */
    suspend fun read(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        call(get(segments(path), query = query), JsonElement.serializer())

    /**
     * Writes to any API path with [method] `POST`, `PUT` or `DELETE`.
     *
     * @return the task's UPID when the change runs as a task, or null
     */
    suspend fun write(method: String, path: String, form: Map<String, String>): String? = when (method) {
        "DELETE" -> callForTask(delete(segments(path), form))
        "POST" -> callForTask(post(segments(path), form))
        "PUT" -> callForTask(put(segments(path), form))
        else -> throw IllegalArgumentException("Unsupported method $method")
    }

    /**
     * The segments of [path]. A segment that itself holds a slash, such as
     * the `10.0.0.0/8` of an IP set entry, is written as `%2F`; see
     * [encodePathValue].
     */
    private fun segments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }.map(::decodePercent)

    /**
     * Returns the data of a write to any path, for calls that answer with
     * more than a task ID, such as creating an API token, which returns its
     * secret once.
     */
    suspend fun writeForData(method: String, path: String, form: Map<String, String>): JsonElement? {
        val request = when (method) {
            "POST" -> post(segments(path), form)
            "PUT" -> put(segments(path), form)
            else -> throw IllegalArgumentException("Unsupported method $method")
        }
        val raw = execute(request)
        if (raw.code in 200..299) {
            val data = try {
                PveJson.decodeFromString(ENVELOPE, raw.body).data
            } catch (ignored: SerializationException) {
                null
            } catch (ignored: IllegalArgumentException) {
                null
            }
            return data?.takeUnless { it is JsonNull }
        }
        return decode(raw, JsonElement.serializer())
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
            is ConsoleTarget.NodeShell, is ConsoleTarget.NodeUpgrade -> listOf("nodes", target.node)
            is ConsoleTarget.Guest -> guestPath(target.node, target.kind, target.vmid)
        }
        val form = if (target is ConsoleTarget.NodeUpgrade) mapOf("cmd" to "upgrade") else emptyMap()
        val ticket = call(post(base + "termproxy", form), TermProxyTicket.serializer())
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

    // --- Guest lifecycle -------------------------------------------------
    //
    // Each call starts a task on the server and returns its UPID; follow it
    // with awaitTask. A call that returns null finished at once.

    /** The guest's snapshots, with the `current` row last. */
    suspend fun snapshots(node: String, kind: ResourceKind, vmid: Int): List<Snapshot> =
        call(get(guestPath(node, kind, vmid) + "snapshot"), ListSerializer(Snapshot.serializer()))

    /** @param includeMemory saves a running virtual machine's memory too, so a rollback resumes it */
    suspend fun createSnapshot(node: String, kind: ResourceKind, vmid: Int, name: String, description: String?, includeMemory: Boolean): String? {
        val form = buildMap {
            put("snapname", name)
            if (!description.isNullOrBlank()) put("description", description)
            if (kind == ResourceKind.Qemu && includeMemory) put("vmstate", "1")
        }
        return callForTask(post(guestPath(node, kind, vmid) + "snapshot", form))
    }

    suspend fun rollbackSnapshot(node: String, kind: ResourceKind, vmid: Int, name: String): String? =
        callForTask(post(guestPath(node, kind, vmid) + listOf("snapshot", name, "rollback"), emptyMap()))

    suspend fun deleteSnapshot(node: String, kind: ResourceKind, vmid: Int, name: String): String? =
        callForTask(delete(guestPath(node, kind, vmid) + listOf("snapshot", name)))

    /** The storages [node] can use, optionally only those holding [content], such as `backup`. */
    suspend fun nodeStorage(node: String, content: String? = null): List<NodeStorage> {
        val query = content?.let { mapOf("content" to it) }.orEmpty()
        return call(get(listOf("nodes", node, "storage"), query = query), ListSerializer(NodeStorage.serializer()))
    }

    /** The volumes on a storage, optionally only one kind of [content] and one guest's. */
    suspend fun storageContent(node: String, storage: String, content: String? = null, vmid: Int? = null): List<StorageVolume> {
        val query = buildMap {
            if (content != null) put("content", content)
            if (vmid != null) put("vmid", vmid.toString())
        }
        return call(get(listOf("nodes", node, "storage", storage, "content"), query = query), ListSerializer(StorageVolume.serializer()))
    }

    /** Backs up one guest to [storage] now. */
    suspend fun backupNow(
        node: String,
        vmid: Int,
        storage: String,
        mode: BackupMode,
        compression: BackupCompression,
        notes: String? = null,
    ): String? {
        val form = buildMap {
            put("vmid", vmid.toString())
            put("storage", storage)
            put("mode", mode.apiName)
            put("compress", compression.apiName)
            if (!notes.isNullOrBlank()) put("notes-template", notes)
        }
        return callForTask(post(listOf("nodes", node, "vzdump"), form))
    }

    /**
     * Restores a backup [archive] as guest [vmid].
     *
     * @param overwrite replaces an existing guest with that ID, which must be stopped
     * @param storage where the restored disks go, or null for where they were
     */
    suspend fun restore(
        node: String,
        kind: ResourceKind,
        vmid: Int,
        archive: String,
        overwrite: Boolean,
        storage: String? = null,
        start: Boolean = false,
        unique: Boolean = false,
    ): String? {
        val form = buildMap {
            put("vmid", vmid.toString())
            when (kind) {
                ResourceKind.Qemu -> put("archive", archive)
                ResourceKind.Lxc -> {
                    put("ostemplate", archive)
                    put("restore", "1")
                }
                else -> throw IllegalArgumentException("$kind is not a guest")
            }
            if (overwrite) put("force", "1")
            if (storage != null) put("storage", storage)
            if (start) put("start", "1")
            // A copy beside the original needs its own network addresses.
            if (unique) put("unique", "1")
        }
        val type = if (kind == ResourceKind.Qemu) "qemu" else "lxc"
        return callForTask(post(listOf("nodes", node, type), form))
    }

    /** What stands in the way of moving a virtual machine. Containers have no such check. */
    suspend fun migrationCheck(node: String, vmid: Int): MigrationCheck =
        call(get(guestPath(node, ResourceKind.Qemu, vmid) + "migrate"), MigrationCheck.serializer())

    /**
     * Moves a guest to [target]. A running virtual machine moves while it
     * runs; a running container is restarted on the target.
     *
     * @param withLocalDisks copies disks on storage the target does not share
     */
    suspend fun migrate(node: String, kind: ResourceKind, vmid: Int, target: String, running: Boolean, withLocalDisks: Boolean): String? {
        val form = buildMap {
            put("target", target)
            if (running) put(if (kind == ResourceKind.Qemu) "online" else "restart", "1")
            if (kind == ResourceKind.Qemu && withLocalDisks) put("with-local-disks", "1")
        }
        return callForTask(post(guestPath(node, kind, vmid) + "migrate", form))
    }

    /** The lowest guest ID not in use. */
    suspend fun nextId(): Int =
        call(get(listOf("cluster", "nextid")), String.serializer()).trim().toIntOrNull()
            ?: throw PveApiException(STATUS_OK, "The server sent no usable guest ID")

    /**
     * Copies a guest as [newId].
     *
     * @param full copies every disk; otherwise a template's clone shares its disks where the storage allows
     * @param targetNode where the copy goes, or null for this node
     * @param storage where a full copy's disks go, or null for where they are
     */
    suspend fun clone(
        node: String,
        kind: ResourceKind,
        vmid: Int,
        newId: Int,
        name: String?,
        full: Boolean,
        targetNode: String? = null,
        storage: String? = null,
    ): String? {
        val form = buildMap {
            put("newid", newId.toString())
            if (!name.isNullOrBlank()) put(if (kind == ResourceKind.Qemu) "name" else "hostname", name)
            put("full", if (full) "1" else "0")
            if (targetNode != null && targetNode != node) put("target", targetNode)
            if (storage != null && full) put("storage", storage)
        }
        return callForTask(post(guestPath(node, kind, vmid) + "clone", form))
    }

    /** Turns a stopped guest into a template. This cannot be undone. */
    suspend fun convertToTemplate(node: String, kind: ResourceKind, vmid: Int): String? =
        callForTask(post(guestPath(node, kind, vmid) + "template", emptyMap()))

    /**
     * Deletes a stopped guest and its disks.
     *
     * @param purge also removes it from backup jobs, replication and high availability
     */
    suspend fun deleteGuest(node: String, kind: ResourceKind, vmid: Int, purge: Boolean): String? {
        val query = buildMap {
            if (purge) put("purge", "1")
            put("destroy-unreferenced-disks", "1")
        }
        return callForTask(delete(guestPath(node, kind, vmid), query))
    }

    // --- Tasks -----------------------------------------------------------

    /**
     * Recent and running tasks across the cluster, newest first. A Backup
     * Server or Datacenter Manager has no cluster, so its own tasks are read.
     */
    suspend fun clusterTasks(): List<TaskSummary> = if (product == Product.Pve) {
        call(get(listOf("cluster", "tasks")), ListSerializer(TaskSummary.serializer()))
    } else {
        call(get(listOf("nodes", "localhost", "tasks"), query = mapOf("limit" to TASK_LIST_LIMIT.toString())), ListSerializer(TaskSummary.serializer()))
    }

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
                is TicketCredentials -> request.header("Cookie", "${product.cookieName}=${current.ticket}")
                is ApiTokenCredentials -> request.header("Authorization", product.tokenAuthorization(current.tokenId, current.secret))
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

    private fun post(path: List<String>, form: Map<String, String>, authenticated: Boolean = true): Request =
        write("POST", path, form, authenticated)

    private fun put(path: List<String>, form: Map<String, String>): Request = write("PUT", path, form, authenticated = true)

    private fun write(method: String, path: List<String>, form: Map<String, String>, authenticated: Boolean): Request {
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = builder(path, emptyMap(), authenticated).method(method, body)
        // Tickets must prove write requests were not forged; API tokens need no such proof.
        val csrfToken = (credentials as? TicketCredentials)?.csrfToken
        if (authenticated && csrfToken != null) request.header("CSRFPreventionToken", csrfToken)
        return request.build()
    }

    private fun delete(path: List<String>, query: Map<String, String> = emptyMap()): Request {
        val request = builder(path, query, authenticated = true).delete()
        val csrfToken = (credentials as? TicketCredentials)?.csrfToken
        if (csrfToken != null) request.header("CSRFPreventionToken", csrfToken)
        return request.build()
    }

    private suspend fun <T> call(request: Request, deserializer: DeserializationStrategy<T>): T =
        decode(execute(request), deserializer)

    /** For calls that start a task: its UPID, or null when the server finished the work at once. */
    private suspend fun callForTask(request: Request): String? = taskFrom(execute(request))

    private fun taskFrom(raw: RawResponse): String? {
        if (raw.code in 200..299) {
            val data = try {
                PveJson.decodeFromString(ENVELOPE, raw.body).data
            } catch (ignored: SerializationException) {
                null
            } catch (ignored: IllegalArgumentException) {
                null
            }
            if (data == null || data is JsonNull) return null
        }
        return decode(raw, String.serializer())
    }

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

        const val MAX_DNS_SERVERS = 3
        const val TASK_LIST_LIMIT = 100
        const val UPLOAD_CHUNK = 64 * 1024

        /** After the last byte, the server stores the file before it answers. */
        const val UPLOAD_READ_TIMEOUT_S = 300L
        val ENVELOPE = ApiEnvelope.serializer(JsonElement.serializer())

        /** Fallback for servers that deliver the ticket only as a cookie. */
        fun ticketFromCookies(setCookies: List<String>, cookieName: String): String? {
            for (header in setCookies) {
                val pair = header.substringBefore(';').trim()
                val name = pair.substringBefore('=')
                if (name == cookieName || name == "__Host-$cookieName") {
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

/** Writes a value for one segment of a path given to [PveClient.read] or [PveClient.write], so slashes in it stay inside it. */
fun encodePathValue(value: String): String = buildString {
    for (char in value) {
        when (char) {
            '%' -> append("%25")
            '/' -> append("%2F")
            else -> append(char)
        }
    }
}

private fun decodePercent(segment: String): String {
    if ('%' !in segment) return segment
    val out = StringBuilder()
    var index = 0
    while (index < segment.length) {
        val char = segment[index]
        val hex = if (char == '%' && index + 2 <= segment.lastIndex) segment.substring(index + 1, index + 3).toIntOrNull(16) else null
        if (hex != null) {
            out.append(hex.toChar())
            index += 3
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}
