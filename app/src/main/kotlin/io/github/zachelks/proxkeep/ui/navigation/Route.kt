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

    /** The open remote desktop. */
    @Serializable
    data object Desktop : Route
}
