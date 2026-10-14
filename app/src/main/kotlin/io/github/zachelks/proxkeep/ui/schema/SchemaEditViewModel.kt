package io.github.zachelks.proxkeep.ui.schema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.Upid
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.schema.ApiMethod
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException

/**
 * What a schema-driven edit screen changes.
 *
 * @property path the object's API path, such as `/nodes/pve1/network/vmbr0`
 * @property schemaPath the path's template in the schema, such as `/nodes/{node}/network/{iface}`
 * @property method `PUT` to change an object that exists, read first from [path];
 *   `POST` to create one, starting empty
 * @property exclude settings the screen does not offer
 * @property presets values a new object starts with, such as its type
 * @property read whether to read the object first; a `PUT` that adds rather
 *   than changes, such as a permission, does not
 */
data class SchemaEditRequest(
    val path: String,
    val schemaPath: String,
    val method: String,
    val exclude: Set<String> = emptySet(),
    val presets: Map<String, String> = emptyMap(),
    val read: Boolean = method == "PUT",
)

/** The form for one API object, built from its method in the server's schema. */
class SchemaEditViewModel(
    private val servers: ServerRepository,
    private val schemas: SchemaRepository,
    private val refreshSignal: RefreshSignal,
    val request: SchemaEditRequest,
) : ViewModel() {

    private val _form = MutableStateFlow<Loadable<SchemaForm>>(Loadable.Loading)
    val form: StateFlow<Loadable<SchemaForm>> = _form.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saved = MutableStateFlow(false)

    /**
     * Whether the change has been saved, so the screen can close. It is
     * state, not an event, so a screen that was not collecting at that
     * moment, for example during a rotation, still sees it.
     */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _secret = MutableStateFlow<String?>(null)

    /**
     * A secret the server returned once, such as a new API token's, which
     * the person must copy before the screen closes.
     */
    val secret: StateFlow<String?> = _secret.asStateFlow()

    /** The person has seen the secret; the screen can close. */
    fun secretShown() {
        _secret.value = null
        _saved.value = true
    }

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var method: ApiMethod? = null
    private var digest: String? = null

    init {
        load()
    }

    fun load() {
        _form.value = Loadable.Loading
        viewModelScope.launch {
            _form.value = try {
                val found = schemas.schema().method(request.schemaPath, request.method)
                    ?: throw IOException("The server's schema has no ${request.method} ${request.schemaPath}")
                method = found
                val values = if (request.read) {
                    val current = servers.request { it.read(request.path) } as? JsonObject ?: JsonObject(emptyMap())
                    digest = (current["digest"] as? JsonPrimitive)?.content
                    flatten(current)
                } else {
                    emptyMap()
                }
                val exclude = request.exclude + pathParameters(request.schemaPath) + "digest"
                val fields = SchemaForm.fieldsFor(found, values, exclude)
                Loadable.Loaded(SchemaForm(fields, values, values + request.presets.filterKeys { key -> fields.any { it.key == key } }))
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    fun edit(key: String, value: String) {
        _form.update { state -> (state as? Loadable.Loaded)?.let { it.copy(value = it.value.with(key, value)) } ?: state }
    }

    fun save() {
        val form = (_form.value as? Loadable.Loaded)?.value ?: return
        val method = method ?: return
        if (!form.isValid || _saving.value) return
        val changes = form.changes
        val parameters = LinkedHashMap(changes.set)
        if (changes.delete.isNotEmpty() && method.parameter("delete") != null) parameters["delete"] = changes.delete.joinToString(",")
        if (digest != null && method.parameter("digest") != null) parameters["digest"] = digest!!
        _saving.value = true
        viewModelScope.launch {
            try {
                val data = servers.request { it.writeForData(request.method, request.path, parameters) }
                val task = (data as? JsonPrimitive)?.content?.let(Upid::parse)
                val returned = ((data as? JsonObject)?.get("value") as? JsonPrimitive)?.content
                if (task != null) {
                    val status = servers.request { it.awaitTask(task.node, task.text) }
                    if (!status.succeeded) {
                        _messages.emit(UiMessage(R.string.config_save_failed, status.exitstatus))
                        return@launch
                    }
                }
                refreshSignal.request()
                if (returned != null) _secret.value = returned else _saved.value = true
            } catch (e: PveApiException) {
                if (e.parameterErrors.isNotEmpty()) {
                    _form.update { state ->
                        (state as? Loadable.Loaded)?.let { it.copy(value = it.value.copy(serverErrors = it.value.serverErrors + e.parameterErrors)) } ?: state
                    }
                }
                _messages.emit(e.toUiMessage())
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _saving.value = false
            }
        }
    }

    companion object {
        private const val MESSAGE_BUFFER = 4

        /** The names in a path template, such as `node` and `iface` in `/nodes/{node}/network/{iface}`. */
        fun pathParameters(template: String): Set<String> =
            Regex("\\{([^}]+)}").findAll(template).map { it.groupValues[1] }.toSet()

        /**
         * The values of an API object as form text. Yes-or-no values become
         * `1` and `0`, the way the server reads them back; lists become
         * comma-separated text; nested objects are left out.
         */
        fun flatten(json: JsonObject): Map<String, String> = buildMap {
            for ((key, element) in json) {
                text(element)?.let { put(key, it) }
            }
        }

        private fun text(element: JsonElement): String? = when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.booleanOrNull?.takeIf { !element.isString }?.let { if (it) "1" else "0" } ?: element.content
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(",")
            is JsonObject -> null
        }
    }
}
