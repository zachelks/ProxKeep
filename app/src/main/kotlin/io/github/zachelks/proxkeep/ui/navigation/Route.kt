package io.github.zachelks.proxkeep.ui.navigation

import kotlinx.serialization.Serializable

/**
 * The app's full screens. [Main] is always at the bottom of the back stack;
 * the others open over it and return to it.
 *
 * Routes are saved with the back stack, so they hold IDs, never data.
 */
@Serializable
sealed interface Route {
    /** The four top-level destinations, with the navigation bar or rail. */
    @Serializable
    data object Main : Route

    @Serializable
    data object Settings : Route

    /** The editor for the terminal's extra-keys row, opened from Settings. */
    @Serializable
    data object ExtraKeys : Route

    /**
     * Adds a server, or signs in to a saved one.
     *
     * @property profileId the saved server to sign in to, or null to add a new one
     */
    @Serializable
    data class SignIn(val profileId: String? = null) : Route

    /** The open console. */
    @Serializable
    data object Terminal : Route

    /** A guest's snapshots. @property resourceId the guest's cluster resource ID, such as `qemu/100` */
    @Serializable
    data class Snapshots(val resourceId: String) : Route

    /** A guest's backups, and backing it up now. */
    @Serializable
    data class Backups(val resourceId: String) : Route

    /** A guest's hardware and settings. */
    @Serializable
    data class GuestConfig(val resourceId: String) : Route

    /** The new virtual machine wizard. */
    @Serializable
    data object CreateVm : Route

    /** The new container wizard. */
    @Serializable
    data object CreateContainer : Route

    /** The datacenter's scheduled backup jobs. */
    @Serializable
    data object BackupJobs : Route

    /** One storage on one node. */
    @Serializable
    data class Storage(val node: String, val storage: String) : Route

    /** One node's management screen. */
    @Serializable
    data class Node(val node: String) : Route

    /**
     * A form built from the server's schema for one API object.
     * See [io.github.zachelks.proxkeep.ui.schema.SchemaEditRequest].
     */
    @Serializable
    data class SchemaEdit(
        val title: String,
        val path: String,
        val schemaPath: String,
        val method: String,
        val exclude: List<String> = emptyList(),
        val presets: Map<String, String> = emptyMap(),
        val read: Boolean = method == "PUT",
        val digest: String? = null,
    ) : Route

    /** A datacenter collection, such as the firewall's aliases. See [io.github.zachelks.proxkeep.ui.datacenter.Collections]. */
    @Serializable
    data class Collection(val specId: String, val args: Map<String, String> = emptyMap(), val title: String? = null) : Route

    /** A guest's firewall: options, rules, aliases, IP sets and log. */
    @Serializable
    data class GuestFirewall(val resourceId: String) : Route

    /** The datacenter's sections. */
    @Serializable
    data object Datacenter : Route

    /** Ceph's health and capacity. */
    @Serializable
    data object Ceph : Route

    /** One datastore of a Backup Server: its space, namespaces and backup groups. */
    @Serializable
    data class Datastore(val store: String) : Route

    /** One backup group's snapshots on a Backup Server, such as `vm/100` in a namespace. */
    @Serializable
    data class BackupGroup(val store: String, val ns: String, val type: String, val id: String) : Route

    /** The open remote desktop. */
    @Serializable
    data object Desktop : Route
}
