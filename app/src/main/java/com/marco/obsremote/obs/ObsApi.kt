package com.marco.obsremote.obs

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Una escena de OBS. */
data class Scene(val name: String)

/** Una fuente con control de audio. */
data class AudioInput(
    val name: String,
    val muted: Boolean,
    val volume: Float
)

/** Resultado de pedir la lista de escenas. */
data class SceneList(val scenes: List<String>, val current: String?)

/**
 * Operaciones concretas sobre OBS usando [ObsWebSocket].
 *
 * Todos los metodos son `suspend` y lanzan [ObsException] si OBS responde con error.
 */
class ObsApi(private val ws: ObsWebSocket) {

    /** Devuelve las escenas existentes y cual esta al aire. */
    suspend fun getSceneList(): SceneList {
        val response = ws.request(Requests.GET_SCENE_LIST)
        val array: JSONArray = response.optJSONArray("scenes") ?: JSONArray()

        val names = buildList {
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("sceneName").orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }
        val current = response.optString("currentProgramSceneName").takeIf { it.isNotEmpty() }
        return SceneList(names, current)
    }

    /** Pone una escena al aire. */
    suspend fun setCurrentScene(sceneName: String) {
        ws.request(
            Requests.SET_CURRENT_PROGRAM_SCENE,
            JSONObject().put("sceneName", sceneName)
        )
    }

    /**
     * Devuelve solo las fuentes que tienen control de audio.
     *
     * `GetInputList` incluye tambien fuentes de video (camaras, imagenes...), y esas
     * no responden a `GetInputVolume`. En vez de mantener una lista de "tipos de audio"
     * distinta para cada sistema operativo, preguntamos a todas y nos quedamos con las
     * que si contestan. Es robusto y no depende de la plataforma donde corra OBS.
     */
    suspend fun getAudioInputs(): List<AudioInput> = coroutineScope {
        val response = ws.request(Requests.GET_INPUT_LIST)
        val array: JSONArray = response.optJSONArray("inputs") ?: JSONArray()

        val names = buildList {
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("inputName").orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }

        names.map { name ->
            async { runCatching { loadAudioInput(name) }.getOrNull() }
        }.awaitAll().filterNotNull()
    }

    private suspend fun loadAudioInput(name: String): AudioInput {
        val key = JSONObject().put("inputName", name)
        val volume = ws.request(Requests.GET_INPUT_VOLUME, key)
        val mute = ws.request(Requests.GET_INPUT_MUTE, key)
        return AudioInput(
            name = name,
            muted = mute.optBoolean("inputMuted", false),
            volume = volume.optDouble("inputVolumeMul", 1.0).toFloat().coerceIn(0f, 1f)
        )
    }

    suspend fun setVolume(inputName: String, volume: Float) {
        ws.request(
            Requests.SET_INPUT_VOLUME,
            JSONObject()
                .put("inputName", inputName)
                .put("inputVolumeMul", volume.toDouble())
        )
    }

    suspend fun toggleMute(inputName: String) {
        ws.request(Requests.TOGGLE_INPUT_MUTE, JSONObject().put("inputName", inputName))
    }
}
