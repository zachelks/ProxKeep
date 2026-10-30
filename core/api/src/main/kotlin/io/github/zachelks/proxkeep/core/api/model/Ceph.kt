package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.parseLenientLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Ceph's health and capacity, from `GET /cluster/ceph/status`, which passes
 * on what `ceph status` reports. Its shape changes between Ceph releases, so
 * it is read by hand and what is missing stays null.
 *
 * @property health `HEALTH_OK`, `HEALTH_WARN` or `HEALTH_ERR`
 * @property checks each health warning's name and summary
 * @property placementGroups how many placement groups are in each state, such as `active+clean`
 */
data class CephStatus(
    val health: String?,
    val checks: List<Pair<String, String>>,
    val monitors: Int?,
    val quorum: List<String>,
    val osds: Int?,
    val osdsUp: Int?,
    val osdsIn: Int?,
    val placementGroups: List<Pair<String, Long>>,
    val bytesUsed: Long?,
    val bytesTotal: Long?,
) {
    val isHealthy: Boolean
        get() = health == "HEALTH_OK"

    companion object {
        fun fromJson(json: JsonObject): CephStatus {
            val health = json.obj("health")
            val checks = health?.obj("checks")?.mapNotNull { (name, check) ->
                val message = (check as? JsonObject)?.obj("summary")?.text("message") ?: return@mapNotNull null
                name to message
            }.orEmpty()
            // Older releases nest the OSD counts one level deeper.
            val osdmap = json.obj("osdmap")?.let { it.obj("osdmap") ?: it }
            val pgmap = json.obj("pgmap")
            val states = (pgmap?.get("pgs_by_state") as? JsonArray)?.mapNotNull { element ->
                val state = element as? JsonObject ?: return@mapNotNull null
                val name = state.text("state_name") ?: return@mapNotNull null
                name to (state.long("count") ?: 0)
            }.orEmpty()
            return CephStatus(
                health = health?.text("status"),
                checks = checks,
                monitors = (json.obj("monmap")?.get("mons") as? JsonArray)?.size ?: json.obj("monmap")?.long("num_mons")?.toInt(),
                quorum = (json["quorum_names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                osds = osdmap?.long("num_osds")?.toInt(),
                osdsUp = osdmap?.long("num_up_osds")?.toInt(),
                osdsIn = osdmap?.long("num_in_osds")?.toInt(),
                placementGroups = states,
                bytesUsed = pgmap?.long("bytes_used"),
                bytesTotal = pgmap?.long("bytes_total"),
            )
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        private fun JsonObject.long(key: String): Long? = text(key)?.let(::parseLenientLong)

    }
}
