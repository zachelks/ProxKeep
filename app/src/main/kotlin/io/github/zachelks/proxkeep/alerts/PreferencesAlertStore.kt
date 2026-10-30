package io.github.zachelks.proxkeep.alerts

import android.content.Context
import androidx.core.content.edit
import io.github.zachelks.proxkeep.data.AlertStore
import io.github.zachelks.proxkeep.data.ServerAlertState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Keeps what the last background check saw in the app's preferences. */
class PreferencesAlertStore(context: Context) : AlertStore {
    private val preferences = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), ServerAlertState.serializer())

    override fun read(): Map<String, ServerAlertState> {
        val text = preferences.getString(KEY_STATE, null) ?: return emptyMap()
        return try {
            Json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    override fun write(state: Map<String, ServerAlertState>) {
        preferences.edit { putString(KEY_STATE, Json.encodeToString(serializer, state)) }
    }

    private companion object {
        const val KEY_STATE = "state"
    }
}
