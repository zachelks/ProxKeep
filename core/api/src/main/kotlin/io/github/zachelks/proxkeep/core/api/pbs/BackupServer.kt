package io.github.zachelks.proxkeep.core.api.pbs

import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.encodePathValue
import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import io.github.zachelks.proxkeep.core.api.json.PveJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/** A Backup Server's API names the server itself `localhost`. */
const val PBS_NODE = "localhost"

/**
 * One datastore's space, from `GET /status/datastore-usage`.
 *
 * @property estimatedFullDate when the datastore will be full at its current growth, in seconds since the epoch;
 *   0 or negative when it is not growing
 * @property error why the datastore cannot be read, such as an unmounted removable datastore
 */
@Serializable
data class DatastoreUsage(
    val store: String,
    @Serializable(with = LenientLongSerializer::class)
    val total: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val used: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val avail: Long? = null,
    @SerialName("estimated-full-date")
    @Serializable(with = LenientLongSerializer::class)
    val estimatedFullDate: Long? = null,
    val error: String? = null,
    @SerialName("gc-status")
    val gcStatus: GcStatus? = null,
)

/**
 * What the last garbage collection found.
 *
 * @property upid the task that ran it
 * @property removedBytes what it freed
 * @property pendingBytes what it will free on the next run, once chunks are old enough
 */
@Serializable
data class GcStatus(
    val upid: String? = null,
    @SerialName("removed-bytes")
    @Serializable(with = LenientLongSerializer::class)
    val removedBytes: Long? = null,
    @SerialName("pending-bytes")
    @Serializable(with = LenientLongSerializer::class)
    val pendingBytes: Long? = null,
    @SerialName("disk-bytes")
    @Serializable(with = LenientLongSerializer::class)
    val diskBytes: Long? = null,
    @SerialName("index-data-bytes")
    @Serializable(with = LenientLongSerializer::class)
    val indexDataBytes: Long? = null,
) {
    /** How many times smaller the stored chunks are than the data they hold. */
    val deduplicationFactor: Double?
        get() {
            val stored = diskBytes ?: return null
            val logical = indexDataBytes ?: return null
            return if (stored > 0) logical.toDouble() / stored else null
        }
}

/** One namespace of a datastore. The root namespace is the empty string. */
@Serializable
data class BackupNamespace(val ns: String = "", val comment: String? = null)

/**
 * The backups of one guest or host in a datastore, from
 * `GET /admin/datastore/{store}/groups`.
 *
 * @property type `vm`, `ct` or `host`
 * @property id the guest ID or host name
 * @property lastBackup the newest snapshot's time, in seconds since the epoch
 */
@Serializable
data class BackupGroup(
    @SerialName("backup-type")
    val type: String,
    @SerialName("backup-id")
    val id: String,
    @SerialName("last-backup")
    @Serializable(with = LenientLongSerializer::class)
    val lastBackup: Long? = null,
    @SerialName("backup-count")
    @Serializable(with = LenientLongSerializer::class)
    val count: Long? = null,
    val owner: String? = null,
    val comment: String? = null,
) {
    val key: String
        get() = "$type/$id"
}

/** Whether a snapshot's last verification passed. */
@Serializable
data class Verification(val state: String? = null, val upid: String? = null) {
    val passed: Boolean
        get() = state == "ok"
}

/** One file of a snapshot, such as a disk image's index. */
@Serializable
data class SnapshotFile(
    val filename: String,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long? = null,
    @SerialName("crypt-mode")
    val cryptMode: String? = null,
)

/**
 * One backup snapshot, from `GET /admin/datastore/{store}/snapshots`.
 *
 * @property time when it was taken, in seconds since the epoch; with [type] and [id] it names the snapshot
 * @property protected whether pruning and deleting leave it alone
 */
@Serializable
data class BackupSnapshot(
    @SerialName("backup-type")
    val type: String,
    @SerialName("backup-id")
    val id: String,
    @SerialName("backup-time")
    @Serializable(with = LenientLongSerializer::class)
    val time: Long,
    val comment: String? = null,
    val verification: Verification? = null,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long? = null,
    val owner: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val protected: Boolean = false,
    val files: List<SnapshotFile> = emptyList(),
) {
    /** True when any of its files is encrypted. */
    val encrypted: Boolean
        get() = files.any { it.cryptMode == "encrypt" }
}

/**
 * What a prune would do to one snapshot, from a dry run.
 *
 * @property keep whether the snapshot stays
 */
@Serializable
data class PruneResult(
    @SerialName("backup-type")
    val type: String,
    @SerialName("backup-id")
    val id: String,
    @SerialName("backup-time")
    @Serializable(with = LenientLongSerializer::class)
    val time: Long,
    @Serializable(with = LenientBooleanSerializer::class)
    val keep: Boolean,
    @Serializable(with = LenientBooleanSerializer::class)
    val protected: Boolean = false,
)

/**
 * How many snapshots a prune keeps in each period. A null count leaves the
 * period out; with every count null, the server keeps everything.
 */
data class PruneOptions(
    val last: Int? = null,
    val hourly: Int? = null,
    val daily: Int? = null,
    val weekly: Int? = null,
    val monthly: Int? = null,
    val yearly: Int? = null,
) {
    val isEmpty: Boolean
        get() = toParameters().isEmpty()

    fun toParameters(): Map<String, String> = buildMap {
        last?.let { put("keep-last", it.toString()) }
        hourly?.let { put("keep-hourly", it.toString()) }
        daily?.let { put("keep-daily", it.toString()) }
        weekly?.let { put("keep-weekly", it.toString()) }
        monthly?.let { put("keep-monthly", it.toString()) }
        yearly?.let { put("keep-yearly", it.toString()) }
    }
}

/**
 * The server itself, from `GET /nodes/localhost/status`.
 *
 * @property cpu the share of processor time in use, from 0 to 1
 */
@Serializable
data class BackupServerStatus(
    val cpu: Double? = null,
    val memory: MemoryUsage? = null,
    val root: MemoryUsage? = null,
    @Serializable(with = LenientLongSerializer::class)
    val uptime: Long? = null,
    val kversion: String? = null,
    val info: Info? = null,
) {
    @Serializable
    data class MemoryUsage(
        @Serializable(with = LenientLongSerializer::class)
        val total: Long? = null,
        @Serializable(with = LenientLongSerializer::class)
        val used: Long? = null,
    )

    @Serializable
    data class Info(val fingerprint: String? = null)
}

private fun datastorePath(store: String, vararg rest: String) =
    (listOf("admin", "datastore", encodePathValue(store)) + rest).joinToString("/", prefix = "/")

private fun namespaceQuery(ns: String): Map<String, String> = if (ns.isEmpty()) emptyMap() else mapOf("ns" to ns)

private fun snapshotQuery(ns: String, snapshot: BackupSnapshot): Map<String, String> =
    namespaceQuery(ns) + mapOf("backup-type" to snapshot.type, "backup-id" to snapshot.id, "backup-time" to snapshot.time.toString())

private fun groupQuery(ns: String, group: BackupGroup): Map<String, String> =
    namespaceQuery(ns) + mapOf("backup-type" to group.type, "backup-id" to group.id)

private fun <T> decodeList(element: JsonElement, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
    PveJson.decodeFromJsonElement(ListSerializer(serializer), element)

/** Every datastore's space and last garbage collection. */
suspend fun PveClient.datastoreUsage(): List<DatastoreUsage> =
    decodeList(read("/status/datastore-usage"), DatastoreUsage.serializer())

suspend fun PveClient.backupServerStatus(): BackupServerStatus =
    PveJson.decodeFromJsonElement(BackupServerStatus.serializer(), read("/nodes/$PBS_NODE/status"))

/** A datastore's namespaces, the root among them. */
suspend fun PveClient.namespaces(store: String): List<BackupNamespace> =
    decodeList(read(datastorePath(store, "namespace")), BackupNamespace.serializer())

suspend fun PveClient.backupGroups(store: String, ns: String = ""): List<BackupGroup> =
    decodeList(read(datastorePath(store, "groups"), namespaceQuery(ns)), BackupGroup.serializer())

/** One group's snapshots, or the whole namespace's when [group] is null. */
suspend fun PveClient.backupSnapshots(store: String, ns: String = "", group: BackupGroup? = null): List<BackupSnapshot> {
    val query = if (group == null) namespaceQuery(ns) else groupQuery(ns, group)
    return decodeList(read(datastorePath(store, "snapshots"), query), BackupSnapshot.serializer())
}

/** Starts garbage collection, which frees the chunks no snapshot uses any more. Returns the task's UPID. */
suspend fun PveClient.startGarbageCollection(store: String): String? =
    write("POST", datastorePath(store, "gc"), emptyMap())

/**
 * Starts verifying a whole namespace, or one snapshot when [snapshot] is set.
 *
 * @param skipVerified leaves out snapshots whose last verification passed
 */
suspend fun PveClient.verify(store: String, ns: String = "", snapshot: BackupSnapshot? = null, skipVerified: Boolean = false): String? {
    val form = (if (snapshot == null) namespaceQuery(ns) else snapshotQuery(ns, snapshot)) +
        (if (skipVerified) mapOf("ignore-verified" to "1") else emptyMap())
    return write("POST", datastorePath(store, "verify"), form)
}

/**
 * Prunes one group: removes the snapshots [options] does not keep. With
 * [dryRun], nothing is removed and the result says what would be.
 */
suspend fun PveClient.pruneGroup(store: String, ns: String, group: BackupGroup, options: PruneOptions, dryRun: Boolean): List<PruneResult> {
    val form = groupQuery(ns, group) + options.toParameters() + (if (dryRun) mapOf("dry-run" to "1") else emptyMap())
    val data = writeForData("POST", datastorePath(store, "prune"), form) ?: return emptyList()
    return decodeList(data, PruneResult.serializer())
}

/** Prunes every group of a namespace as a task. Returns the task's UPID. */
suspend fun PveClient.pruneDatastore(store: String, ns: String, options: PruneOptions): String? =
    write("POST", datastorePath(store, "prune-datastore"), namespaceQuery(ns) + options.toParameters())

suspend fun PveClient.deleteSnapshot(store: String, ns: String, snapshot: BackupSnapshot) {
    write("DELETE", datastorePath(store, "snapshots"), snapshotQuery(ns, snapshot))
}

/** Deletes a group and all its snapshots, except protected ones. */
suspend fun PveClient.deleteGroup(store: String, ns: String, group: BackupGroup) {
    write("DELETE", datastorePath(store, "groups"), groupQuery(ns, group))
}

suspend fun PveClient.setProtected(store: String, ns: String, snapshot: BackupSnapshot, protected: Boolean) {
    write("PUT", datastorePath(store, "protected"), snapshotQuery(ns, snapshot) + ("protected" to if (protected) "1" else "0"))
}
