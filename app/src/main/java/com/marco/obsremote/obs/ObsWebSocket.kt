package com.marco.obsremote.obs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Cliente del protocolo obs-websocket v5 sobre OkHttp.
 *
 * El protocolo es un WebSocket que habla JSON. Sigue este flujo:
 *
 *   1. El cliente se conecta al WebSocket.
 *   2. El servidor manda `Hello` (op 0) con su rpcVersion y, si hay contrasena,
 *      un `challenge` y un `salt`.
 *   3. El cliente responde `Identify` (op 1) con la autenticacion calculada.
 *   4. El servidor confirma con `Identified` (op 2).
 *   5. A partir de ahi: `Request` (op 6) / `RequestResponse` (op 7) y `Event` (op 5).
 *
 * La autenticacion es: base64(sha256(base64(sha256(password + salt)) + challenge)).
 */
class ObsWebSocket {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    private var password: String = ""

    @Volatile
    private var rpcVersion: Int = 1

    /** Peticiones enviadas que aun esperan respuesta, indexadas por requestId. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val requestCounter = AtomicLong(0)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ObsEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ObsEvent> = _events.asSharedFlow()

    /**
     * Abre una conexion nueva. Si habia una abierta, la cierra antes.
     * El resultado real llega por [state] y [events].
     */
    fun connect(host: String, port: Int, pass: String) {
        closeSocket()
        password = pass
        _state.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        webSocket = client.newWebSocket(request, listener)
    }

    /** Cierra la conexion actual y cancela las peticiones en vuelo. */
    fun disconnect() {
        closeSocket()
        failPending("Desconectado")
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Envia una peticion y espera su respuesta.
     *
     * @param type nombre del request (por ejemplo `GetSceneList`).
     * @param data parametros del request, o `null` si no lleva.
     * @throws ObsException si OBS responde con error o si no hay conexion.
     */
    suspend fun request(
        type: String,
        data: JSONObject? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): JSONObject {
        val id = requestCounter.incrementAndGet().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val payload = JSONObject().apply {
            put("op", Op.REQUEST)
            put("d", JSONObject().apply {
                put("requestType", type)
                put("requestId", id)
                if (data != null) put("requestData", data)
            })
        }

        if (webSocket?.send(payload.toString()) != true) {
            pending.remove(id)
            throw ObsException(-1, "No hay conexion con OBS")
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
    }

    // ---------------------------------------------------------------------
    // Interno
    // ---------------------------------------------------------------------

    private fun closeSocket() {
        webSocket?.close(WS_NORMAL_CLOSURE, "Cierre solicitado por la app")
        webSocket = null
    }

    private fun failPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(ObsException(-1, reason)) }
        pending.clear()
    }

    private val listener = object : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(text)
            } catch (_: Exception) {
                // Un mensaje malformado no debe tumbar la conexion.
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failPending(t.message ?: "Error de red")
            _state.value = ConnectionState.Error(
                t.message?.takeIf { it.isNotBlank() } ?: "No se pudo conectar con OBS"
            )
            this@ObsWebSocket.webSocket = null
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            failPending("Conexion cerrada ($code)")
            if (_state.value !is ConnectionState.Error) {
                _state.value = ConnectionState.Disconnected
            }
            this@ObsWebSocket.webSocket = null
        }
    }

    private fun handleMessage(text: String) {
        val root = JSONObject(text)
        val op = root.optInt("op", -1)
        val data = root.optJSONObject("d") ?: JSONObject()

        when (op) {
            Op.HELLO -> handleHello(data)
            Op.IDENTIFIED -> _state.value = ConnectionState.Connected
            Op.EVENT -> handleEvent(data)
            Op.REQUEST_RESPONSE -> handleResponse(data)
        }
    }

    private fun handleHello(data: JSONObject) {
        rpcVersion = data.optInt("rpcVersion", 1)

        val identify = JSONObject().apply {
            put("rpcVersion", rpcVersion)
            put("eventSubscriptions", EventSubscription.WANTED)
        }

        val auth = data.optJSONObject("authentication")
        if (auth != null) {
            if (password.isEmpty()) {
                // OBS exige contrasena pero la app no tiene ninguna configurada.
                // Cerramos con un mensaje claro en vez de dejar la conexion colgada.
                // (No usamos disconnect() porque sobreescribiria este estado de error.)
                closeSocket()
                failPending("Se requiere contrasena")
                _state.value = ConnectionState.Error("OBS pide contrasena. Configurala en Ajustes.")
                return
            }
            identify.put(
                "authentication",
                buildAuthString(
                    password = password,
                    salt = auth.optString("salt"),
                    challenge = auth.optString("challenge")
                )
            )
        }

        val message = JSONObject().apply {
            put("op", Op.IDENTIFY)
            put("d", identify)
        }
        webSocket?.send(message.toString())
    }

    private fun handleEvent(data: JSONObject) {
        val type = data.optString("eventType")
        if (type.isEmpty()) return
        val payload = data.optJSONObject("eventData") ?: JSONObject()
        _events.tryEmit(ObsEvent(type, payload))
    }

    private fun handleResponse(data: JSONObject) {
        val id = data.optString("requestId")
        val deferred = pending.remove(id) ?: return

        val status = data.optJSONObject("requestStatus") ?: JSONObject()
        if (status.optBoolean("result", false)) {
            deferred.complete(data.optJSONObject("responseData") ?: JSONObject())
        } else {
            deferred.completeExceptionally(
                ObsException(status.optInt("code", -1), status.optString("comment"))
            )
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val WS_NORMAL_CLOSURE = 1000

        /** Calcula la cadena de autenticacion que exige obs-websocket v5. */
        internal fun buildAuthString(password: String, salt: String, challenge: String): String {
            val secret = sha256Base64(password + salt)
            return sha256Base64(secret + challenge)
        }

        private fun sha256Base64(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}
