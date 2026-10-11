package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientDoubleSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import io.github.zachelks.proxkeep.core.api.json.PveJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Serializable
internal data class Sample(
    @Serializable(with = LenientBooleanSerializer::class)
    val flag: Boolean = false,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long = 0,
    @Serializable(with = LenientIntSerializer::class)
    val count: Int = 0,
    @Serializable(with = LenientDoubleSerializer::class)
    val load: Double = 0.0,
    @Serializable(with = LenientBooleanSerializer::class)
    val maybe: Boolean? = null,
)

class LenientSerializersTest {

    private fun parse(json: String): Sample = PveJson.decodeFromString<Sample>(json)

    @Test
    fun booleansAcceptNumbersStringsAndLiterals() {
        assertEquals(true, parse("""{"flag":1}""").flag)
        assertEquals(false, parse("""{"flag":0}""").flag)
        assertEquals(true, parse("""{"flag":"1"}""").flag)
        assertEquals(false, parse("""{"flag":"0"}""").flag)
        assertEquals(true, parse("""{"flag":true}""").flag)
        assertEquals(false, parse("""{"flag":false}""").flag)
        assertEquals(true, parse("""{"flag":"TRUE"}""").flag)
    }

    @Test
    fun nullableBooleansKeepNull() {
        assertNull(parse("""{"maybe":null}""").maybe)
        assertNull(parse("""{}""").maybe)
        assertEquals(true, parse("""{"maybe":"1"}""").maybe)
    }

    @Test
    fun integersAcceptStringsAndFloats() {
        assertEquals(16777216000L, parse("""{"size":"16777216000"}""").size)
        assertEquals(16777216000L, parse("""{"size":16777216000}""").size)
        assertEquals(1500000000L, parse("""{"size":1.5e9}""").size)
        assertEquals(42L, parse("""{"size":42.0}""").size)
        assertEquals(100, parse("""{"count":"100"}""").count)
        assertEquals(100, parse("""{"count":100}""").count)
    }

    @Test
    fun doublesAcceptStrings() {
        assertEquals(0.25, parse("""{"load":"0.25"}""").load)
        assertEquals(0.25, parse("""{"load":0.25}""").load)
        assertEquals(8.0, parse("""{"load":8}""").load)
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<SerializationException> { parse("""{"flag":"maybe"}""") }
        assertFailsWith<SerializationException> { parse("""{"size":"big"}""") }
        assertFailsWith<SerializationException> { parse("""{"count":99999999999}""") }
        assertFailsWith<SerializationException> { parse("""{"load":"NaN"}""") }
        assertFailsWith<SerializationException> { parse("""{"size":{"nested":1}}""") }
    }

    @Test
    fun booleansAreWrittenAsOneOrZero() {
        assertEquals("""{"flag":1}""", PveJson.encodeToString(Sample(flag = true)))
        assertEquals("""{"maybe":0}""", PveJson.encodeToString(Sample(maybe = false)))
    }
}
