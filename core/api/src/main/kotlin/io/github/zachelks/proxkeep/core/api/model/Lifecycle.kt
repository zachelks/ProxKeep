package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of `GET …/snapshot`. The list always ends with a row named
 * `current`, which is the guest's present state, not a snapshot.
 *
 * @property snaptime seconds since the epoch
 * @property vmstate whether the virtual machine's memory was saved with it
 */
@Serializable
data class Snapshot(
    val name: String,
    val description: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val snaptime: Long? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val vmstate: Boolean? = null,
    val parent: String? = null,
) {
    val isCurrent: Boolean
        get() = name == CURRENT

    companion object {
        const val CURRENT = "current"

        /** Whether [name] is one the server accepts for a new snapshot: a letter, then letters, digits, `-` and `_`. */
        fun isValidName(name: String): Boolean =
            name.length in 2..40 && name.first().isLetter() && name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } &&
                name != CURRENT
    }
}

/** One row of `GET /nodes/{node}/storage`: a storage as one node sees it. */
@Serializable
data class NodeStorage(
    val storage: String,
    val type: String? = null,
    /** Comma-separated kinds of content, such as `images,rootdir` or `backup`. */
    val content: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val active: Boolean? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val enabled: Boolean? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val shared: Boolean? = null,
    @Serializable(with = LenientLongSerializer::class)
    val avail: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val total: Long? = null,
) {
    val contentTypes: Set<String>
        get() = content?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** Whether the storage can be used now; a missing flag counts as yes. */
    val isUsable: Boolean
        get() = active != false && enabled != false
}

/**
 * One volume from `GET /nodes/{node}/storage/{storage}/content`, such as a
 * backup archive.
 *
 * @property volid the volume's ID, for example `local:backup/vzdump-qemu-100-2026_10_14-03_00_02.vma.zst`
 * @property ctime seconds since the epoch
 */
@Serializable
data class StorageVolume(
    val volid: String,
    val format: String? = null,
    val content: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val ctime: Long? = null,
    @Serializable(with = LenientIntSerializer::class)
    val vmid: Int? = null,
    val notes: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val protected: Boolean? = null,
)

/**
 * Response of `GET /nodes/{node}/qemu/{vmid}/migrate`: what stands in the
 * way of moving a virtual machine.
 *
 * @property allowedNodes nodes the guest can move to
 * @property localDisks disks on storage the target nodes do not share, which must be copied
 * @property localResources devices tied to this node, such as passed-through hardware, which prevent moving
 */
@Serializable
data class MigrationCheck(
    @Serializable(with = LenientBooleanSerializer::class)
    val running: Boolean? = null,
    @SerialName("allowed_nodes")
    val allowedNodes: List<String>? = null,
    @SerialName("local_disks")
    val localDisks: List<LocalDisk>? = null,
    @SerialName("local_resources")
    val localResources: List<String>? = null,
)

@Serializable
data class LocalDisk(
    val volid: String,
    @SerialName("drivename")
    val driveName: String? = null,
)

/** How a backup treats a running guest. */
enum class BackupMode(internal val apiName: String) {
    /** Keeps it running; the backup reads a consistent point in time. */
    Snapshot("snapshot"),

    /** Pauses it briefly. */
    Suspend("suspend"),

    /** Shuts it down for the backup and starts it again after. */
    Stop("stop"),
}

enum class BackupCompression(internal val apiName: String) {
    Zstd("zstd"),
    Lzo("lzo"),
    Gzip("gzip"),
    None("0"),
}
