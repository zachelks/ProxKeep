package io.github.zachelks.proxkeep.ui.schema

import io.github.zachelks.proxkeep.core.api.schema.ApiMethod
import io.github.zachelks.proxkeep.core.api.schema.ParamSchema
import io.github.zachelks.proxkeep.core.api.schema.ValueProblem
import io.github.zachelks.proxkeep.core.api.schema.check

/**
 * One setting in a schema-driven form.
 *
 * @property key the key as sent, such as `net1` for the schema's `net[n]`
 */
data class FormField(val key: String, val schema: ParamSchema)

/** What saving a form sends: values to set and keys to remove. */
data class FormChanges(val set: Map<String, String>, val delete: List<String>) {
    val isEmpty: Boolean
        get() = set.isEmpty() && delete.isEmpty()
}

/**
 * The values of a schema-driven form and what the person changed.
 *
 * An empty value means the key is not set, so the server's default applies.
 * Emptying a key that had a value removes it.
 *
 * @property serverErrors the server's reasons for refusing values, by key, from the last save
 */
data class SchemaForm(
    val fields: List<FormField>,
    val initial: Map<String, String>,
    val values: Map<String, String> = initial,
    val serverErrors: Map<String, String> = emptyMap(),
) {
    fun value(key: String): String = values[key].orEmpty()

    fun with(key: String, value: String): SchemaForm =
        copy(values = values + (key to value), serverErrors = serverErrors - key)

    fun field(key: String): FormField? = fields.firstOrNull { it.key == key }

    fun problem(key: String): ValueProblem? = field(key)?.schema?.check(value(key))

    val isValid: Boolean
        get() = fields.all { problem(it.key) == null }

    fun isChanged(key: String): Boolean = value(key) != initial[key].orEmpty()

    val changes: FormChanges
        get() {
            val changed = fields.map { it.key }.filter(::isChanged)
            return FormChanges(
                set = changed.filter { value(it).isNotEmpty() }.associateWith(::value),
                delete = changed.filter { value(it).isEmpty() },
            )
        }

    /** A form whose saved values are the current ones, after a save. */
    fun saved(): SchemaForm = copy(initial = values, serverErrors = emptyMap())

    companion object {
        /**
         * Parameters every config method has that are about the call, not settings.
         */
        val CONTROL_KEYS = setOf("node", "vmid", "digest", "delete", "revert", "skiplock", "background_delay", "force")

        /**
         * A field for each setting [method] takes, apart from [CONTROL_KEYS] and [exclude].
         * A numbered setting such as `serial[n]` gets one field for each key [values] has, such as `serial0`.
         *
         * @param exclude keys and numbered keys' base names that a hand-designed screen edits instead
         * @param extraKeys numbered keys to offer even when [values] lacks them
         */
        fun fieldsFor(
            method: ApiMethod,
            values: Map<String, String>,
            exclude: Set<String> = emptySet(),
            extraKeys: Collection<String> = emptyList(),
        ): List<FormField> =
            method.parameters
                .filterNot { it.name in CONTROL_KEYS || it.name in exclude || it.baseName in exclude || it.isAlias }
                .flatMap { schema ->
                    if (!schema.isIndexed) {
                        listOf(FormField(schema.name, schema))
                    } else {
                        (values.keys + extraKeys).distinct().filter(schema::matches)
                            .sortedBy { it.removePrefix(schema.baseName).toInt() }
                            .map { FormField(it, schema) }
                    }
                }

        /** The first free key for a numbered setting such as `serial[n]`. */
        fun nextKey(schema: ParamSchema, taken: Collection<String>): String {
            var index = 0
            while ("${schema.baseName}$index" in taken) index++
            return "${schema.baseName}$index"
        }
    }
}
