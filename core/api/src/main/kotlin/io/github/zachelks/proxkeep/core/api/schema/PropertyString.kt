package io.github.zachelks.proxkeep.core.api.schema

/**
 * A property string, the text Proxmox stores composite settings in, such as
 * `virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1`.
 *
 * Keys keep the order they were written in, so a value read and written
 * back unchanged comes out the same apart from spelling out aliases.
 */
class PropertyString(val values: Map<String, String>) {

    operator fun get(key: String): String? = values[key]

    fun flag(key: String): Boolean? = values[key]?.let { it == "1" || it.equals("true", ignoreCase = true) || it == "on" }

    /** A copy with [key] set to [value], or removed when [value] is null or empty. */
    fun with(key: String, value: String?): PropertyString {
        val copy = LinkedHashMap(values)
        if (value.isNullOrEmpty()) copy.remove(key) else copy[key] = value
        return PropertyString(copy)
    }

    /**
     * Writes the values in the form the server reads: the default key's value
     * first and bare, then `key=value` pairs.
     */
    fun encode(schema: ParamSchema): String {
        val remaining = LinkedHashMap(values)
        val items = mutableListOf<String>()

        // `virtio=BC:24:…` names the model by its key and holds the address.
        val named = schema.properties.firstOrNull { it.keyAlias != null && it.alias != null && remaining[it.keyAlias] == it.name }
        val namedValue = named?.alias?.let { remaining[it] }
        if (named != null && namedValue != null) {
            items += "${named.name}=$namedValue"
            remaining.remove(named.keyAlias)
            remaining.remove(named.alias)
        }

        val defaultKey = schema.properties.firstOrNull { it.defaultKey }?.name
        if (defaultKey != null) remaining.remove(defaultKey)?.let { items.add(0, it) }

        remaining.forEach { (key, value) -> items += "$key=$value" }
        return items.joinToString(",")
    }

    companion object {
        val Empty = PropertyString(emptyMap())

        /** Reads [text] by the keys [schema] defines. Unknown keys are kept as written. */
        fun parse(text: String, schema: ParamSchema): PropertyString {
            val values = LinkedHashMap<String, String>()
            val defaultKey = schema.properties.firstOrNull { it.defaultKey }?.name
            for (item in text.split(',')) {
                val part = item.trim()
                if (part.isEmpty()) continue
                val separator = part.indexOf('=')
                if (separator < 0) {
                    values[defaultKey ?: part] = if (defaultKey != null) part else ""
                    continue
                }
                val key = part.substring(0, separator).trim()
                val value = part.substring(separator + 1)
                val property = schema.property(key)
                when {
                    property?.keyAlias != null && property.alias != null -> {
                        values[property.keyAlias] = key
                        values[property.alias] = value
                    }
                    property?.alias != null -> values[property.alias] = value
                    else -> values[key] = value
                }
            }
            return PropertyString(values)
        }
    }
}
