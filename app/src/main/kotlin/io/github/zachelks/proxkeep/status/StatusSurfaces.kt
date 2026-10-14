package io.github.zachelks.proxkeep.status

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import io.github.zachelks.proxkeep.data.StatusSnapshot
import io.github.zachelks.proxkeep.data.StatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Keeps the snapshot in the app's preferences. */
class PreferencesStatusStore(context: Context) : StatusStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): StatusSnapshot? {
        val text = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            Json.decodeFromString(StatusSnapshot.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun write(snapshot: StatusSnapshot?) {
        preferences.edit {
            if (snapshot == null) remove(KEY_SNAPSHOT) else putString(KEY_SNAPSHOT, Json.encodeToString(StatusSnapshot.serializer(), snapshot))
        }
    }

    internal companion object {
        const val PREFERENCES = "status"
        const val KEY_SNAPSHOT = "snapshot"
    }
}

/**
 * The home screen widget and the Quick Settings tile: whether either is
 * placed, which keeps the background checks running, and what they show.
 */
class StatusSurfaces(private val context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesStatusStore.PREFERENCES, Context.MODE_PRIVATE)
    val store: StatusStore = PreferencesStatusStore(context)

    private val _placed = MutableStateFlow(widgetPlaced || tilePlaced)

    /** Whether a widget or the tile is placed, so the snapshot should be kept fresh. */
    val placed: StateFlow<Boolean> = _placed.asStateFlow()

    private val widgetPlaced: Boolean
        get() = preferences.getBoolean(KEY_WIDGET, false)

    private val tilePlaced: Boolean
        get() = preferences.getBoolean(KEY_TILE, false)

    fun setWidgetPlaced(placed: Boolean) {
        preferences.edit { putBoolean(KEY_WIDGET, placed) }
        _placed.value = placed || tilePlaced
    }

    fun setTilePlaced(placed: Boolean) {
        preferences.edit { putBoolean(KEY_TILE, placed) }
        _placed.value = placed || widgetPlaced
    }

    /**
     * Stores [snapshot] and redraws the widget and tile. The app reads the
     * cluster every few seconds while it is open, so an unchanged snapshot is
     * only stored again after a while.
     */
    suspend fun update(snapshot: StatusSnapshot?) {
        val previous = store.read()
        val unchanged = previous != null && snapshot != null &&
            previous.copy(updatedAt = 0) == snapshot.copy(updatedAt = 0) && snapshot.updatedAt - previous.updatedAt < RESTORE_MS
        if (unchanged || (previous == null && snapshot == null)) return
        store.write(snapshot)
        redraw()
    }

    /** Draws the widget and tile again, for example after the app lock is turned on or off. */
    suspend fun redraw() {
        StatusWidget().updateAll(context)
        TileService.requestListeningState(context, ComponentName(context, StatusTileService::class.java))
    }

    private companion object {
        const val KEY_WIDGET = "widget_placed"
        const val KEY_TILE = "tile_placed"
        const val RESTORE_MS = 5L * 60L * 1000L
    }
}
