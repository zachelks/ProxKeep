package io.github.zachelks.proxkeep.core.api.json

import kotlinx.serialization.json.Json

/**
 * JSON configuration for every Proxmox API response.
 *
 * Unknown keys are ignored so that newer server versions, which add fields
 * freely, do not break parsing.
 */
val PveJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
