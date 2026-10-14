package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.parseLenientBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the signed-in user or API token may do, from `GET /access/permissions`.
 *
 * The server lists the paths that carry permissions, such as `/`, `/vms/100`
 * or `/pool/lab`, each with its effective privileges. A privilege flagged as
 * propagating also applies below its path. For any other path, the most
 * specific listed path above it decides, as on the server.
 *
 * The server checks every request itself. These answers only decide what the
 * app offers, so an action is never shown that is bound to be refused.
 */
class Permissions(
    /** Privileges by path, each with whether it propagates to paths below. */
    private val byPath: Map<String, Map<String, Boolean>>,
) {
    /** Whether [privilege] is granted on [path]. */
    fun has(path: String, privilege: String): Boolean {
        var current = normalize(path)
        var exact = true
        while (true) {
            val privileges = byPath[current]
            if (privileges != null) {
                val propagates = privileges[privilege] ?: return false
                return exact || propagates
            }
            if (current == ROOT) return false
            current = current.substringBeforeLast('/').ifEmpty { ROOT }
            exact = false
        }
    }

    /** Whether [privilege] is granted on any of [paths]. */
    fun hasAny(paths: List<String>, privilege: String): Boolean = paths.any { has(it, privilege) }

    override fun toString(): String = "Permissions(${byPath.keys})"

    companion object {
        private const val ROOT = "/"

        /** The paths whose permissions apply to a guest: its own, and its pool's when it is in one. */
        fun guestPaths(vmid: Int, pool: String?): List<String> =
            listOfNotNull("/vms/$vmid", pool?.takeIf { it.isNotBlank() }?.let { "/pool/$it" })

        fun nodePath(node: String): String = "/nodes/$node"

        fun fromJson(json: JsonObject): Permissions = Permissions(
            json.mapKeys { (path, _) -> normalize(path) }.mapValues { (_, privileges) ->
                (privileges as? JsonObject).orEmpty().mapValues { (_, propagate) ->
                    (propagate as? JsonPrimitive)?.content?.let(::parseLenientBoolean) ?: false
                }
            },
        )

        private fun normalize(path: String): String {
            val trimmed = path.trim().trimEnd('/')
            return when {
                trimmed.isEmpty() -> ROOT
                trimmed.startsWith('/') -> trimmed
                else -> "/$trimmed"
            }
        }
    }
}

/** Privilege names used by the app. */
object Privilege {
    const val VM_POWER_MGMT = "VM.PowerMgmt"
    const val VM_CONSOLE = "VM.Console"
    const val SYS_CONSOLE = "Sys.Console"
    const val SYS_AUDIT = "Sys.Audit"
    const val SYS_MODIFY = "Sys.Modify"
    const val SYS_POWER_MGMT = "Sys.PowerMgmt"
    const val VM_SNAPSHOT = "VM.Snapshot"
    const val VM_SNAPSHOT_ROLLBACK = "VM.Snapshot.Rollback"
    const val VM_BACKUP = "VM.Backup"
    const val VM_MIGRATE = "VM.Migrate"
    const val VM_CLONE = "VM.Clone"
    const val VM_ALLOCATE = "VM.Allocate"
    const val VM_AUDIT = "VM.Audit"
    const val VM_CONFIG_CPU = "VM.Config.CPU"
    const val VM_CONFIG_MEMORY = "VM.Config.Memory"
    const val VM_CONFIG_DISK = "VM.Config.Disk"
    const val VM_CONFIG_NETWORK = "VM.Config.Network"
    const val VM_CONFIG_CDROM = "VM.Config.CDROM"
    const val VM_CONFIG_OPTIONS = "VM.Config.Options"
    const val VM_CONFIG_HWTYPE = "VM.Config.HWType"
    const val VM_CONFIG_CLOUDINIT = "VM.Config.Cloudinit"
    const val DATASTORE_ALLOCATE_SPACE = "Datastore.AllocateSpace"
    const val DATASTORE_ALLOCATE_TEMPLATE = "Datastore.AllocateTemplate"
    const val DATASTORE_ALLOCATE = "Datastore.Allocate"
}
