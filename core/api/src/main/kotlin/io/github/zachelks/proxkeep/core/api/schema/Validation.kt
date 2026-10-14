package io.github.zachelks.proxkeep.core.api.schema

import java.util.regex.PatternSyntaxException

/** Why a value does not fit its schema. */
enum class ProblemKind {
    Required,
    NotANumber,
    NotAWholeNumber,
    BelowMinimum,
    AboveMaximum,
    NotAChoice,
    TooShort,
    TooLong,
    WrongFormat,

    /** Commas separate the items of a property string, so a value inside one cannot hold them. */
    HasComma,
}

/**
 * A value that does not fit its schema.
 *
 * @property key inside a property string, the key whose value is wrong
 * @property limit the bound that was crossed, for [ProblemKind.BelowMinimum],
 *   [ProblemKind.AboveMaximum], [ProblemKind.TooShort] and [ProblemKind.TooLong]
 */
data class ValueProblem(
    val kind: ProblemKind,
    val key: String? = null,
    val limit: Double? = null,
)

/**
 * Checks [value] the way the server will, as far as the schema says.
 * Named formats such as `pve-storage-id` are left to the server.
 *
 * @return null when the value fits, or when it is empty and may be left out
 */
fun ParamSchema.check(value: String): ValueProblem? = check(value, insidePropertyString = false)

private fun ParamSchema.check(value: String, insidePropertyString: Boolean): ValueProblem? {
    if (value.isEmpty()) return if (!optional && default == null) ValueProblem(ProblemKind.Required) else null
    if (insidePropertyString && ',' in value) return ValueProblem(ProblemKind.HasComma)
    if (enum.isNotEmpty() && value !in enum) return ValueProblem(ProblemKind.NotAChoice)
    when (type) {
        ParamType.Integer -> {
            val number = value.toLongOrNull() ?: return ValueProblem(ProblemKind.NotAWholeNumber)
            return range(number.toDouble())
        }
        ParamType.Number -> {
            val number = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return ValueProblem(ProblemKind.NotANumber)
            return range(number)
        }
        ParamType.Boolean -> return if (value in BOOLEAN_TEXT) null else ValueProblem(ProblemKind.NotAChoice)
        ParamType.String -> Unit
        else -> return null
    }
    minLength?.let { if (value.length < it) return ValueProblem(ProblemKind.TooShort, limit = it.toDouble()) }
    maxLength?.let { if (value.length > it) return ValueProblem(ProblemKind.TooLong, limit = it.toDouble()) }
    pattern?.let { if (!matchesPattern(it, value)) return ValueProblem(ProblemKind.WrongFormat) }
    if (isPropertyString) {
        val parsed = PropertyString.parse(value, this)
        for ((key, item) in parsed.values) {
            val property = property(key) ?: continue
            property.check(item, insidePropertyString = true)?.let { return it.copy(key = it.key ?: key) }
        }
        for (property in properties) {
            if (!property.optional && property.default == null && !property.isAlias && property.keyAlias == null && parsed[property.name] == null) {
                return ValueProblem(ProblemKind.Required, key = property.name)
            }
        }
    }
    return null
}

private fun ParamSchema.range(number: Double): ValueProblem? {
    minimum?.let { if (number < it) return ValueProblem(ProblemKind.BelowMinimum, limit = it) }
    maximum?.let { if (number > it) return ValueProblem(ProblemKind.AboveMaximum, limit = it) }
    return null
}

/** The server anchors its patterns at both ends. Patterns Java cannot read are left to the server. */
private fun matchesPattern(pattern: String, value: String): Boolean = try {
    Regex(pattern).matches(value)
} catch (ignored: PatternSyntaxException) {
    true
}

private val BOOLEAN_TEXT = setOf("0", "1", "true", "false", "on", "off", "yes", "no")
