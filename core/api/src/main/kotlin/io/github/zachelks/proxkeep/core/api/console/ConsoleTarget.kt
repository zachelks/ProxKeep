package io.github.zachelks.proxkeep.core.api.console

import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import kotlinx.serialization.Serializable

/** Something a text console can be opened on. */
sealed interface ConsoleTarget {
    val node: String

    /** A login shell on the node itself. */
    data class NodeShell(override val node: String) : ConsoleTarget

    /**
     * A guest's console: a container's terminal, or a virtual machine's
     * serial port. A virtual machine needs a serial port configured for this.
     */
    data class Guest(override val node: String, val kind: ResourceKind, val vmid: Int) : ConsoleTarget
}

/**
 * Response of `POST …/termproxy`: where the terminal is listening and the
 * one-time ticket that admits a connection to it for a few seconds.
 */
@Serializable
internal data class TermProxyTicket(
    @Serializable(with = LenientIntSerializer::class)
    val port: Int,
    val ticket: String,
    /** The user or API token the ticket was issued to. */
    val user: String,
)
