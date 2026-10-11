package io.github.zachelks.proxkeep.core.api.model

import kotlinx.serialization.Serializable

/**
 * Wrapper around every Proxmox API response body.
 *
 * Successful calls carry [data]. Failed calls carry a null [data] and usually
 * [errors], keyed by the request parameter that was rejected.
 */
@Serializable
data class ApiEnvelope<T>(
    val data: T? = null,
    val errors: Map<String, String>? = null,
    val message: String? = null,
)
