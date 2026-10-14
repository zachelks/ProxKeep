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
    ) : Route

    /** The open remote desktop. */
    @Serializable
    data object Desktop : Route
}
