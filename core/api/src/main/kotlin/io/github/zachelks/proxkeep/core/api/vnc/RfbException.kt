package io.github.zachelks.proxkeep.core.api.vnc

import java.io.IOException

/** The VNC server refused the connection or sent something this client cannot read. */
class RfbException(message: String, cause: Throwable? = null) : IOException(message, cause)
