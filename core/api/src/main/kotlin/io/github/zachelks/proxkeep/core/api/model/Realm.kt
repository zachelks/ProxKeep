package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One authentication realm from `GET /access/domains`, for example `pam`
 * (Linux PAM) or `pve` (the Proxmox VE authentication server).
 */
@Serializable
data class Realm(
    val realm: String,
    val type: String,
    val comment: String? = null,
    @SerialName("default")
    @Serializable(with = LenientBooleanSerializer::class)
    val isDefault: Boolean = false,
) {
    /** Realms that sign in through an external web page, not with a password. */
    val usesWebLogin: Boolean
        get() = type == "openid"
}
