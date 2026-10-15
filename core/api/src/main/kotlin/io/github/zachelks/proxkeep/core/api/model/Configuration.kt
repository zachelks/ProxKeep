package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.parseLenientBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One row of `GET …/pending`: a configuration key, its value now, and the
 * change waiting for the guest's next start.
 *
 * @property value the value in effect, null when the key is not set
 * @property pending the value that applies after a restart, null when none waits
 * @property deleting whether the key is removed at the next restart
 */
data class PendingChange(
    val key: String,
    val value: String?,
    val pending: String?,
    val deleting: Boolean,
) {
    val isPending: Boolean
        get() = pending != null || deleting

    companion object {
        internal fun fromJson(json: JsonObject): PendingChange? {
            val key = json.text("key") ?: return null
            return PendingChange(
                key = key,
                value = json.text("value"),
                pending = json.text("pending"),
                deleting = json.text("delete")?.let { it != "0" && parseLenientBoolean(it) != false } ?: false,
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    }
}

/** One row of `GET /nodes/{node}/network`. */
@Serializable
data class NodeNetwork(
    val iface: String,
    /** Such as `bridge`, `OVSBridge`, `bond`, `eth` or `vlan`. */
    val type: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val active: Boolean? = null,
    val cidr: String? = null,
    val comments: String? = null,
) {
    val isBridge: Boolean
        get() = type == "bridge" || type == "OVSBridge"
}
