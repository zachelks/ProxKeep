package io.github.zachelks.proxkeep.core.api.model

import kotlinx.serialization.Serializable

/** Response of `GET /version`. */
@Serializable
data class VersionInfo(
    val version: String,
    val release: String? = null,
    val repoid: String? = null,
) {
    /** Major version number, for example 9 for "9.2.1". Null if unparseable. */
    val major: Int?
        get() = version.substringBefore('.').toIntOrNull()
}
