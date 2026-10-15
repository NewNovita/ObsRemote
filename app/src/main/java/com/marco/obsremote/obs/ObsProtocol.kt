package com.marco.obsremote.obs

import org.json.JSONObject

/** Estado de la conexion con obs-websocket. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** Error devuelto por OBS en un RequestResponse, o error local de transporte. */
class ObsException(val code: Int, message: String?) :
    Exception(message ?: "Error de OBS (codigo $code)")

/** Evento asincrono enviado por OBS (op 5). */
data class ObsEvent(val type: String, val data: JSONObject)

// --- Codigos de operacion del protocolo obs-websocket v5 ---
internal object Op {
    const val HELLO = 0
    const val IDENTIFY = 1
    const val IDENTIFIED = 2
    const val EVENT = 5
    const val REQUEST = 6
    const val REQUEST_RESPONSE = 7
}

/**
 * Mascara de suscripcion a eventos (bitmask).
 * Solo pedimos lo que la app necesita: cambios de escena y cambios de audio.
 * No pedimos los eventos de "alto volumen" (meters de audio) para no saturar el WiFi.
 */
internal object EventSubscription {
    const val GENERAL = 1 shl 0
    const val SCENES = 1 shl 2
    const val INPUTS = 1 shl 3
    const val WANTED = GENERAL or SCENES or INPUTS
}

// --- Nombres de requests del protocolo ---
internal object Requests {
    const val GET_SCENE_LIST = "GetSceneList"
    const val SET_CURRENT_PROGRAM_SCENE = "SetCurrentProgramScene"
    const val GET_INPUT_LIST = "GetInputList"
    const val GET_INPUT_VOLUME = "GetInputVolume"
    const val SET_INPUT_VOLUME = "SetInputVolume"
    const val GET_INPUT_MUTE = "GetInputMute"
    const val SET_INPUT_MUTE = "SetInputMute"
    const val TOGGLE_INPUT_MUTE = "ToggleInputMute"
}

// --- Nombres de eventos ---
internal object Events {
    const val CURRENT_PROGRAM_SCENE_CHANGED = "CurrentProgramSceneChanged"
    const val INPUT_MUTE_STATE_CHANGED = "InputMuteStateChanged"
    const val INPUT_VOLUME_CHANGED = "InputVolumeChanged"
}
