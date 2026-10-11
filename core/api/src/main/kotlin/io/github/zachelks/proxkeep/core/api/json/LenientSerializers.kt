package io.github.zachelks.proxkeep.core.api.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// The Proxmox API is loosely typed: booleans arrive as 0/1, and the same
// numeric field can be a JSON number on one endpoint and a string on another.
// These serializers accept every form the servers are known to send.

private fun Decoder.primitiveContent(expected: String): String {
    val jsonDecoder = this as? JsonDecoder
        ?: throw SerializationException("Lenient serializers only support JSON")
    val element = jsonDecoder.decodeJsonElement()
    if (element !is JsonPrimitive || element is JsonNull) {
        throw SerializationException("Expected $expected but found: $element")
    }
    return element.content.trim()
}

internal fun parseLenientBoolean(raw: String): Boolean? = when (raw.lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> null
}

internal fun parseLenientLong(raw: String): Long? {
    raw.toLongOrNull()?.let { return it }
    val number = raw.toDoubleOrNull() ?: return null
    if (!number.isFinite()) return null
    if (number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()) return null
    return number.toLong()
}

internal fun parseLenientInt(raw: String): Int? {
    val number = parseLenientLong(raw) ?: return null
    if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
    return number.toInt()
}

internal fun parseLenientDouble(raw: String): Double? =
    raw.toDoubleOrNull()?.takeIf { it.isFinite() }

/** Reads `true`, `false`, `0`, `1` and their string forms. Writes `1` or `0`. */
object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("pve.LenientBoolean", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Boolean {
        val raw = decoder.primitiveContent("a boolean")
        return parseLenientBoolean(raw)
            ?: throw SerializationException("Not a boolean: $raw")
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeInt(if (value) 1 else 0)
    }
}

/** Reads a JSON number or a numeric string. Fractions are truncated. */
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("pve.LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val raw = decoder.primitiveContent("an integer")
        return parseLenientLong(raw)
            ?: throw SerializationException("Not an integer: $raw")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

/** Reads a JSON number or a numeric string. Fractions are truncated. */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("pve.LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val raw = decoder.primitiveContent("an integer")
        return parseLenientInt(raw)
            ?: throw SerializationException("Not an integer: $raw")
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

/** Reads a JSON number or a numeric string. */
object LenientDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("pve.LenientDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val raw = decoder.primitiveContent("a number")
        return parseLenientDouble(raw)
            ?: throw SerializationException("Not a number: $raw")
    }

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeDouble(value)
    }
}
