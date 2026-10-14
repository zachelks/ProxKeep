package io.github.zachelks.proxkeep.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.Upid
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.schema.ApiSchema
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.schema.SchemaEditViewModel
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** One listed object, its values as text. */
data class CollectionItem(val id: String, val values: Map<String, String>)

/**
 * What the server lets this collection do, from its schema. While the
 * schema is not known, the collection's own description decides.
 */
data class CollectionAbilities(val create: Boolean, val edit: Boolean, val delete: Boolean)

/** One collection of API objects: listing, deleting, and actions on items. Editing and creating open schema forms. */
class CollectionViewModel(
    private val servers: ServerRepository,
    private val schemas: SchemaRepository,
    private val refreshSignal: RefreshSignal,
    val spec: CollectionSpec,
    val args: Map<String, String>,
) : ViewModel() {

    val items: StateFlow<Loadable<List<CollectionItem>>> = follow { client -> parse(client.read(spec.list(args))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    private val _abilities = MutableStateFlow(
        CollectionAbilities(create = spec.canCreate, edit = spec.editable && spec.itemPath != null, delete = spec.deletable && (spec.itemPath != null || spec.aclStyle)),
    )
    val abilities: StateFlow<CollectionAbilities> = _abilities.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                _abilities.value = abilitiesFrom(schemas.schema(), spec, _abilities.value)
            } catch (e: IOException) {
                // Without the schema, the collection's description stands.
            }
        }
    }

    fun retry() = refreshSignal.request()

    fun isReadOnly(item: CollectionItem): Boolean = spec.readOnlyWhen?.let { (key, value) -> item.values[key] == value } == true

    fun delete(item: CollectionItem) = change(R.string.dc_deleted) { client ->
        if (spec.aclStyle) {
            client.write("PUT", "/access/acl", aclRemoval(item.values))
        } else {
            client.write("DELETE", spec.item(args, item.id) ?: return@change null, emptyMap())
        }
    }

    fun run(item: CollectionItem, action: ItemAction) = change(action.done) { client ->
        client.write(action.method, CollectionSpec.fill(action.path, args + (spec.idKey to item.id) + (spec.itemParam.orEmpty() to item.id)), emptyMap())
    }

    private fun change(done: Int, block: suspend (PveClient) -> String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                // Some calls answer with something other than a task, such as a new object's ID.
                val task = servers.request { block(it) }?.let(Upid::parse)
                if (task != null) {
                    val status = servers.request { it.awaitTask(task.node, task.text) }
                    if (!status.succeeded) {
                        _messages.emit(UiMessage(R.string.dc_failed, status.exitstatus))
                        return@launch
                    }
                }
                _messages.emit(UiMessage(done))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    private fun parse(json: kotlinx.serialization.json.JsonElement): List<CollectionItem> {
        val array = json as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val values = (element as? JsonObject)?.let(SchemaEditViewModel::flatten) ?: return@mapNotNull null
            val id = values[spec.idKey] ?: spec.titleKeys.firstNotNullOfOrNull { values[it] } ?: return@mapNotNull null
            CollectionItem(id, values)
        }.sortedWith(compareBy({ it.values[spec.idKey]?.toIntOrNull() ?: Int.MAX_VALUE }, { it.id }))
    }

    private fun <T> follow(read: suspend (PveClient) -> T): Flow<Loadable<T>> = flow {
        var current: Loadable<T> = Loadable.Loading
        emit(current)
        refreshSignal.ticks(REFRESH_MS).collect {
            current = try {
                Loadable.Loaded(servers.request(read))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MESSAGE_BUFFER = 8
        private const val REFRESH_MS = 15_000L

        fun abilitiesFrom(schema: ApiSchema, spec: CollectionSpec, fallback: CollectionAbilities): CollectionAbilities {
            val createPath = if (spec.createWithId) spec.itemPath else spec.listPath
            return CollectionAbilities(
                create = spec.canCreate && (if (spec.aclStyle) schema.method("/access/acl", "PUT") != null else createPath?.let { schema.method(it, "POST") } != null),
                edit = spec.editable && spec.itemPath?.let { schema.method(it, "PUT") } != null,
                delete = spec.deletable && (spec.aclStyle || spec.itemPath?.let { schema.method(it, "DELETE") } != null),
            ).takeIf { schema.endpoint(spec.listPath) != null } ?: fallback
        }

        /** The form that removes one permission: the same entry, with `delete` set. */
        fun aclRemoval(values: Map<String, String>): Map<String, String> {
            val who = when (values["type"]) {
                "group" -> "groups"
                "token" -> "tokens"
                else -> "users"
            }
            return buildMap {
                put("path", values["path"].orEmpty())
                put("roles", values["roleid"].orEmpty())
                put(who, values["ugid"].orEmpty())
                values["propagate"]?.let { put("propagate", it) }
                put("delete", "1")
            }
        }
    }
}
