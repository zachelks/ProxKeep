package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.parseLenientBoolean
import io.github.zachelks.proxkeep.core.api.json.parseLenientLong
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One numbered device line of a guest's configuration, such as `scsi0` or
 * `net1`.
 *
 * @property bus the key without its index, for example `scsi`
 * @property index null for the one device that has none, a container's `rootfs`
 * @property value the raw configuration text, for example
 *   `local-lvm:vm-100-disk-0,size=32G`
 */
data class ConfigDevice(
    val key: String,
    val bus: String,
    val index: Int?,
    val value: String,
) {
    val isCdrom: Boolean
        get() = value.split(',').any { it.trim() == "media=cdrom" }
}

/**
 * Response of `GET /nodes/{node}/{qemu|lxc}/{vmid}/config`.
 *
 * The configuration is a flat set of keys that differs by guest type and
 * grows with every server release, so it is kept as text and read by key.
 *
 * @property digest identifies this version of the configuration; send it
 *   with a change so the server refuses it if someone else changed it first
 */
data class GuestConfig(val values: Map<String, String>, val digest: String? = null) {

    operator fun get(key: String): String? = values[key]

    fun long(key: String): Long? = values[key]?.let(::parseLenientLong)

    fun flag(key: String): Boolean? = values[key]?.let(::parseLenientBoolean)

    /** The free-text notes field. */
    val description: String?
        get() = values["description"]?.takeIf { it.isNotBlank() }

    /**
     * Whether the QEMU guest agent is switched on. The setting is either a
     * bare `1` or a property list such as `enabled=1,fstrim_cloned_disks=1`.
     */
    val agentEnabled: Boolean
        get() {
            val properties = values["agent"]?.split(',')?.map { it.trim() } ?: return false
            return properties.firstOrNull() == "1" || "enabled=1" in properties
        }

    /** Devices on the given buses, in the order the buses are listed and then by index. */
    fun devices(vararg buses: String): List<ConfigDevice> = buses.flatMap { bus ->
        values.mapNotNull { (key, value) ->
            val index = key.removePrefix(bus).takeIf { key.startsWith(bus) && it.isNotEmpty() && it.all(Char::isDigit) }
            index?.toIntOrNull()?.let { ConfigDevice(key, bus, it, value) }
        }.sortedBy { it.index }
    }

    /** Disks and other storage-backed devices, for either guest type. */
    val disks: List<ConfigDevice>
        get() = listOfNotNull(values["rootfs"]?.let { ConfigDevice("rootfs", "rootfs", null, it) }) +
            devices("scsi", "virtio", "sata", "ide", "mp", "efidisk", "tpmstate", "unused")

    val networks: List<ConfigDevice>
        get() = devices("net")

    companion object {
        /** Keeps every plain value. Nested values are dropped. */
        fun fromJson(json: JsonObject): GuestConfig {
            val values = LinkedHashMap<String, String>()
            for ((key, element) in json) {
                if (key == "digest") continue
                if (element is JsonPrimitive && element !is JsonNull) values[key] = element.content
            }
            return GuestConfig(values, (json["digest"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content)
        }
    }
}
