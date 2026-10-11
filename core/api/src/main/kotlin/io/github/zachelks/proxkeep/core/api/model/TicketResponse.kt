package io.github.zachelks.proxkeep.core.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `POST /access/ticket`. */
@Serializable
internal data class TicketResponse(
    val username: String? = null,
    val ticket: String? = null,
    @SerialName("CSRFPreventionToken")
    val csrfToken: String? = null,
    val clustername: String? = null,
)
