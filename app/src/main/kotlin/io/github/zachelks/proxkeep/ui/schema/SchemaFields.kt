@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.schema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.schema.ParamSchema
import io.github.zachelks.proxkeep.core.api.schema.ParamType
import io.github.zachelks.proxkeep.core.api.schema.ProblemKind
import io.github.zachelks.proxkeep.core.api.schema.PropertyString
import io.github.zachelks.proxkeep.core.api.schema.ValueProblem

/**
 * An editor for one setting, chosen by its type: a switch for a yes-or-no
 * setting, a menu for a list of choices, a group of editors for a property
 * string, and a text field for the rest.
 *
 * @param serverError the server's reason for refusing the last saved value
 */
@Composable
fun SchemaFieldEditor(
    key: String,
    schema: ParamSchema,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    problem: ValueProblem? = null,
    serverError: String? = null,
    enabled: Boolean = true,
) {
    val label = settingLabel(key, schema)
    when {
        schema.type == ParamType.Boolean -> BooleanField(label, schema, value, onValueChange, modifier, problem, serverError, enabled)
        schema.enum.isNotEmpty() -> ChoiceField(label, schema, value, onValueChange, modifier, problem, serverError, enabled)
        schema.isPropertyString -> PropertyStringField(label, schema, value, onValueChange, modifier, problem, serverError, enabled)
        else -> TextValueField(label, schema, value, onValueChange, modifier, problem, serverError, enabled)
    }
}

@Composable
private fun BooleanField(
    label: String,
    schema: ParamSchema,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    problem: ValueProblem?,
    serverError: String?,
    enabled: Boolean,
) {
    val checked = isOn(value.ifEmpty { schema.default.orEmpty() })
    val state = stringResource(if (checked) R.string.state_on else R.string.state_off)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                schema.description?.let { Description(it) }
            }
            Switch(
                checked = checked,
                onCheckedChange = { onValueChange(if (it) "1" else "0") },
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = state
                },
            )
        }
        FieldError(problem, serverError)
    }
}

@Composable
private fun ChoiceField(
    label: String,
    schema: ParamSchema,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    problem: ValueProblem?,
    serverError: String?,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = schema.default?.let { stringResource(R.string.setting_default_value, it) } ?: stringResource(R.string.setting_not_set)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value.ifEmpty { defaultLabel },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = problem != null || serverError != null,
            supportingText = supportingText(schema, problem, serverError),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (schema.optional || schema.default != null) {
                DropdownMenuItem(text = { Text(defaultLabel) }, onClick = {
                    expanded = false
                    onValueChange("")
                })
            }
            schema.enum.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = {
                    expanded = false
                    onValueChange(choice)
                })
            }
        }
    }
}

@Composable
private fun TextValueField(
    label: String,
    schema: ParamSchema,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    problem: ValueProblem?,
    serverError: String?,
    enabled: Boolean,
) {
    val keyboard = when (schema.type) {
        ParamType.Integer -> KeyboardType.Number
        ParamType.Number -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    val long = (schema.maxLength ?: 0) > LONG_TEXT || schema.name == "description"
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        placeholder = schema.default?.let { { Text(it) } },
        singleLine = !long,
        minLines = if (long) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        isError = problem != null || serverError != null,
        supportingText = supportingText(schema, problem, serverError),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A property string, edited one key at a time. */
@Composable
private fun PropertyStringField(
    label: String,
    schema: ParamSchema,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    problem: ValueProblem?,
    serverError: String?,
    enabled: Boolean,
) {
    val parsed = PropertyString.parse(value, schema)
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            schema.description?.let { Description(it) }
            schema.properties.filterNot { it.isAlias || it.keyAlias != null }.forEach { property ->
                SchemaFieldEditor(
                    key = property.name,
                    schema = property,
                    value = parsed[property.name].orEmpty(),
                    onValueChange = { onValueChange(parsed.with(property.name, it).encode(schema)) },
                    problem = problem?.takeIf { it.key == property.name }?.copy(key = null),
                    enabled = enabled,
                )
            }
            FieldError(problem?.takeIf { it.key == null }, serverError)
        }
    }
}

@Composable
private fun Description(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = DESCRIPTION_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FieldError(problem: ValueProblem?, serverError: String?) {
    val message = problem?.let { problemText(it) } ?: serverError?.trim() ?: return
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun supportingText(schema: ParamSchema, problem: ValueProblem?, serverError: String?): (@Composable () -> Unit)? {
    if (problem == null && serverError == null && schema.description == null) return null
    return {
        val error = problem?.let { problemText(it) } ?: serverError?.trim()
        if (error != null) Text(error) else Text(schema.description.orEmpty(), maxLines = DESCRIPTION_LINES, overflow = TextOverflow.Ellipsis)
    }
}

/** A sentence saying what is wrong with a value. */
@Composable
fun problemText(problem: ValueProblem): String {
    val limit = problem.limit?.let(::formatLimit).orEmpty()
    val text = when (problem.kind) {
        ProblemKind.Required -> stringResource(R.string.problem_required)
        ProblemKind.NotANumber -> stringResource(R.string.problem_not_a_number)
        ProblemKind.NotAWholeNumber -> stringResource(R.string.problem_not_a_whole_number)
        ProblemKind.BelowMinimum -> stringResource(R.string.problem_below_minimum, limit)
        ProblemKind.AboveMaximum -> stringResource(R.string.problem_above_maximum, limit)
        ProblemKind.NotAChoice -> stringResource(R.string.problem_not_a_choice)
        ProblemKind.TooShort -> stringResource(R.string.problem_too_short, limit)
        ProblemKind.TooLong -> stringResource(R.string.problem_too_long, limit)
        ProblemKind.WrongFormat -> stringResource(R.string.problem_wrong_format)
        ProblemKind.HasComma -> stringResource(R.string.problem_has_comma)
    }
    return problem.key?.let { stringResource(R.string.problem_in_key, it, text) } ?: text
}

private fun formatLimit(limit: Double): String =
    if (limit == Math.floor(limit) && Math.abs(limit) < 1e15) limit.toLong().toString() else limit.toString()

private fun isOn(value: String): Boolean = value == "1" || value.equals("true", ignoreCase = true) || value == "on" || value == "yes"

private const val LONG_TEXT = 200
private const val DESCRIPTION_LINES = 3
