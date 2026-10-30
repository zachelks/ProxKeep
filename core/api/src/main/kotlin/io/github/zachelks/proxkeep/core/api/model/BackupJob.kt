package io.github.zachelks.proxkeep.core.api.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One row of `GET /cluster/backup`: a scheduled backup job.
 *
 * The job's settings are kept as text, because they are the parameters of
 * a backup and grow with every server release.
 *
 * @property schedule a calendar event such as `daily` or `sat 02:00`
 * @property guests the guest IDs the job backs up; empty when it backs up all guests or a pool
 */
data class BackupJob(
    val id: String,
    val values: Map<String, String>,
) {
    val schedule: String? get() = values["schedule"]
    val storage: String? get() = values["storage"]
    val comment: String? get() = values["comment"]
    val node: String? get() = values["node"]
    val pool: String? get() = values["pool"]
    val mode: String? get() = values["mode"]

    /** Jobs are enabled unless they say otherwise. */
    val enabled: Boolean get() = values["enabled"] != "0"

    /** Whether the job backs up every guest, apart from those it excludes. */
    val all: Boolean get() = values["all"] == "1"

    val guests: List<Int>
        get() = values["vmid"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

    val excluded: List<Int>
        get() = values["exclude"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

    /**
     * The parameters of `POST /nodes/{node}/vzdump` that run this job now:
     * its settings without those about scheduling and the job itself.
     */
    fun runParameters(): Map<String, String> = values.filterKeys { it !in SCHEDULING_KEYS }

    companion object {
        private val SCHEDULING_KEYS = setOf(
            "id", "type", "schedule", "enabled", "comment", "next-run", "repeat-missed", "starttime", "dow", "digest", "node",
        )

        internal fun fromJson(json: JsonObject): BackupJob? {
            val id = (json["id"] as? JsonPrimitive)?.content ?: return null
            val values = LinkedHashMap<String, String>()
            for ((key, element) in json) {
                when (element) {
                    is JsonNull, is JsonObject -> Unit
                    is JsonPrimitive -> values[key] = element.booleanOrNull?.takeIf { !element.isString }?.let { if (it) "1" else "0" } ?: element.content
                    is JsonArray -> values[key] = element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(",")
                }
            }
            return BackupJob(id, values)
        }
    }
}
