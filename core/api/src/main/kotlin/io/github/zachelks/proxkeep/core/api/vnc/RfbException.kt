package io.github.zachelks.proxkeep.core.api.vnc

import java.io.IOException

/** Why a remote desktop ended, in terms the app words for the person. */
enum class RfbProblem {
    /** The server speaks a version or sign-in this client does not. */
    Unsupported,

    /** The server sent data that breaks the protocol. */
    Damaged,

    /** The server asked for more memory than a phone can give, such as a huge screen. */
    TooLarge,

    /** The server refused the connection; [RfbException.serverReason] may say why. */
    Refused,
}

/**
 * The VNC server refused the connection or sent something this client cannot read.
 *
 * The message is for logs. The app shows [problem] in its own words, and
 * [serverReason] when the server gave one.
 */
class RfbException(
    val problem: RfbProblem,
    message: String,
    cause: Throwable? = null,
    val serverReason: String? = null,
) : IOException(message, cause)
