package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.parseLenientLong
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How far back a usage history reaches. The server picks the resolution to match. */
enum class RrdTimeframe(internal val apiName: String) {
    Hour("hour"),
    Day("day"),
    Week("week"),
    Month("month"),
    Year("year"),
}

/**
 * One sample of a usage history, from `GET …/rrddata`.
 *
 * Which metrics a sample holds depends on what it describes and on the
 * server version, so they are kept by name. A metric is missing from a
 * sample when nothing was recorded then, for example while a guest was off.
 *
 * Guests report `cpu` (a fraction of `maxcpu`), `mem` and `maxmem` in bytes,
 * and `netin`, `netout`, `diskread` and `diskwrite` in bytes per second.
 * Nodes report `cpu`, `memused`, `memtotal`, `netin`, `netout`, `loadavg`
 * and `iowait`.
 *
 * @property time seconds since the epoch
 */
data class RrdPoint(val time: Long, val values: Map<String, Double>) {

    operator fun get(metric: String): Double? = values[metric]

    companion object {
        /** Returns null for a sample without a usable time. */
        fun fromJson(json: JsonObject): RrdPoint? {
            val time = (json["time"] as? JsonPrimitive)?.content?.let(::parseLenientLong) ?: return null
            val values = LinkedHashMap<String, Double>()
            for ((key, element) in json) {
                if (key == "time" || element !is JsonPrimitive || element is JsonNull || element.isString && element.content.isEmpty()) continue
                val number = element.content.toDoubleOrNull() ?: continue
                if (!number.isNaN() && !number.isInfinite()) values[key] = number
            }
            return RrdPoint(time, values)
        }
    }
}
