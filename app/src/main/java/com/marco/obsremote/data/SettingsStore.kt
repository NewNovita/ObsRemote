package com.marco.obsremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** Datos de conexion con obs-websocket. */
data class ConnectionSettings(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val password: String = "",
    val autoConnect: Boolean = true
) {
    val isComplete: Boolean get() = host.isNotBlank() && port in 1..65535

    companion object {
        const val DEFAULT_PORT = 4455
    }
}

// El delegate tiene que ser de nivel de archivo (una sola instancia por proceso).
private val Context.dataStore by preferencesDataStore(name = "obs_settings")

/** Guarda y recupera los ajustes de conexion del telefono. */
class SettingsStore(private val context: Context) {

    private val hostKey = stringPreferencesKey("host")
    private val portKey = intPreferencesKey("port")
    private val passwordKey = stringPreferencesKey("password")
    private val autoConnectKey = booleanPreferencesKey("auto_connect")

    suspend fun load(): ConnectionSettings {
        val prefs = context.dataStore.data.first()
        return ConnectionSettings(
            host = prefs[hostKey].orEmpty(),
            port = prefs[portKey] ?: ConnectionSettings.DEFAULT_PORT,
            password = prefs[passwordKey].orEmpty(),
            autoConnect = prefs[autoConnectKey] ?: true
        )
    }

    suspend fun save(settings: ConnectionSettings) {
        context.dataStore.edit { prefs ->
            prefs[hostKey] = settings.host
            prefs[portKey] = settings.port
            prefs[passwordKey] = settings.password
            prefs[autoConnectKey] = settings.autoConnect
        }
    }
}
