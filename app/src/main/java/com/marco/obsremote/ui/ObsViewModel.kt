package com.marco.obsremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marco.obsremote.data.ConnectionSettings
import com.marco.obsremote.data.SettingsStore
import com.marco.obsremote.obs.AudioInput
import com.marco.obsremote.obs.ConnectionState
import com.marco.obsremote.obs.Events
import com.marco.obsremote.obs.ObsApi
import com.marco.obsremote.obs.ObsWebSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class ObsViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocket = ObsWebSocket()
    private val api = ObsApi(webSocket)
    private val settingsStore = SettingsStore(application)

    val connectionState: StateFlow<ConnectionState> = webSocket.state

    private val _settings = MutableStateFlow(ConnectionSettings())
    val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()

    private val _scenes = MutableStateFlow<List<String>>(emptyList())
    val scenes: StateFlow<List<String>> = _scenes.asStateFlow()

    private val _currentScene = MutableStateFlow<String?>(null)
    val currentScene: StateFlow<String?> = _currentScene.asStateFlow()

    private val _audioInputs = MutableStateFlow<List<AudioInput>>(emptyList())
    val audioInputs: StateFlow<List<AudioInput>> = _audioInputs.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Un trabajo de "debounce" por fuente de audio.
     * Sin esto, arrastrar un slider mandaria decenas de peticiones por segundo a OBS.
     */
    private val volumeJobs = mutableMapOf<String, Job>()

    init {
        // Cargar ajustes guardados y, si procede, conectar solo al abrir la app.
        viewModelScope.launch {
            val stored = settingsStore.load()
            _settings.value = stored
            if (stored.isComplete && stored.autoConnect) {
                openConnection(stored)
            }
        }

        // Cuando la conexion se establece, leemos el estado actual de OBS.
        viewModelScope.launch {
            webSocket.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> refresh()
                    is ConnectionState.Error -> _error.value = state.message
                    else -> Unit
                }
            }
        }

        // Escuchamos los cambios que ocurran en OBS (desde el PC o desde otro mando).
        viewModelScope.launch {
            webSocket.events.collect { event -> applyEvent(event.type, event.data) }
        }
    }

    fun connect(settings: ConnectionSettings) {
        if (!settings.isComplete) {
            _error.value = "Falta la IP del PC"
            return
        }
        viewModelScope.launch {
            settingsStore.save(settings)
            _settings.value = settings
            openConnection(settings)
        }
    }

    private suspend fun openConnection(settings: ConnectionSettings) {
        // Limpiamos lo anterior para no mostrar datos de la sesion pasada.
        _scenes.value = emptyList()
        _audioInputs.value = emptyList()
        _currentScene.value = null
        _error.value = null
        webSocket.connect(settings.host, settings.port, settings.password)
    }

    fun disconnect() {
        volumeJobs.values.forEach { it.cancel() }
        volumeJobs.clear()
        webSocket.disconnect()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val sceneList = api.getSceneList()
                _scenes.value = sceneList.scenes
                _currentScene.value = sceneList.current
                _audioInputs.value = api.getAudioInputs()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "No se pudo leer el estado de OBS"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun selectScene(sceneName: String) {
        val previous = _currentScene.value
        _currentScene.value = sceneName // actualizacion optimista
        viewModelScope.launch {
            try {
                api.setCurrentScene(sceneName)
            } catch (e: Exception) {
                _currentScene.value = previous
                _error.value = e.message ?: "No se pudo cambiar de escena"
            }
        }
    }

    fun toggleMute(input: AudioInput) {
        _audioInputs.update { list ->
            list.map { if (it.name == input.name) it.copy(muted = !it.muted) else it }
        }
        viewModelScope.launch {
            try {
                api.toggleMute(input.name)
            } catch (e: Exception) {
                _error.value = e.message ?: "No se pudo cambiar el silencio"
            }
        }
    }

    fun setVolume(inputName: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)

        // Reflejamos el movimiento en la pantalla al instante...
        _audioInputs.update { list ->
            list.map { if (it.name == inputName) it.copy(volume = clamped) else it }
        }

        // ...pero esperamos a que el dedo se detenga antes de molestar a OBS.
        volumeJobs[inputName]?.cancel()
        volumeJobs[inputName] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MS)
            try {
                api.setVolume(inputName, clamped)
            } catch (e: Exception) {
                _error.value = e.message ?: "No se pudo ajustar el volumen"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun applyEvent(type: String, data: JSONObject) {
        when (type) {
            Events.CURRENT_PROGRAM_SCENE_CHANGED -> {
                val name = data.optString("sceneName")
                if (name.isNotEmpty()) _currentScene.value = name
            }

            Events.INPUT_MUTE_STATE_CHANGED -> {
                val name = data.optString("inputName")
                val muted = data.optBoolean("inputMuted")
                _audioInputs.update { list ->
                    list.map { if (it.name == name) it.copy(muted = muted) else it }
                }
            }

            Events.INPUT_VOLUME_CHANGED -> {
                val name = data.optString("inputName")
                // Si el usuario acaba de mover ese slider, ignoramos el eco que devuelve OBS
                // para que el control no "pelee" con el dedo.
                if (volumeJobs[name]?.isActive == true) return
                val volume = data.optDouble("inputVolumeMul", 1.0).toFloat()
                _audioInputs.update { list ->
                    list.map { if (it.name == name) it.copy(volume = volume.coerceIn(0f, 1f)) else it }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocket.disconnect()
    }

    companion object {
        private const val VOLUME_DEBOUNCE_MS = 150L
    }
}
