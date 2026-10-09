package com.example.tvremote

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Protocol (all frames are JSON text):
 *
 *   server -> {"type":"challenge","nonce":"<hex>"}              on connect
 *   client -> {"type":"auth","hmac":"<hex HMAC-SHA256(token, nonce)>"}
 *   server -> {"type":"auth_ok","capabilities":{...}}           or closes with 4003
 *
 *   client -> {"type":"command","command":"DPAD_UP"}
 *   client -> {"type":"mouse","mode":"rel","x":12.0,"y":-4.0}   (rel = pixel deltas)
 *   client -> {"type":"mouse","mode":"abs","x":0.5,"y":0.5}     (abs = normalised 0..1)
 *   client -> {"type":"ping"}                                   server -> {"type":"pong"}
 *   server -> {"type":"error","code":"...","command":"..."}
 *
 * The token itself never crosses the wire — only an HMAC over a fresh server nonce.
 */
class RemoteWebSocketServer(
    port: Int,
    private val currentToken: () -> String,
    private val dispatcher: CommandDispatcher,
    private val callbacks: Callbacks,
) : WebSocketServer(InetSocketAddress(port)) {

    interface Callbacks {
        fun onStarted()
        fun onClientChanged(connected: Boolean)
        fun onTooManyAuthFailures()
        fun onFatalError(e: Exception)
    }

    private class Session(val nonce: String) {
        @Volatile var authenticated = false
    }

    private val sessions = ConcurrentHashMap<WebSocket, Session>()
    @Volatile private var activeClient: WebSocket? = null
    private val authFailures = AtomicInteger(0)
    private val timeouts = Executors.newSingleThreadScheduledExecutor()

    init {
        isReuseAddr = true
        connectionLostTimeout = 20 // seconds; drops half-dead Wi-Fi connections via ping/pong
    }

    override fun onStart() = callbacks.onStarted()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (sessions.size >= MAX_PENDING) {
            conn.close(1013, "BUSY")
            return
        }
        val session = Session(Security.newNonce())
        sessions[conn] = session
        conn.send(JSONObject().put("type", "challenge").put("nonce", session.nonce).toString())
        timeouts.schedule(
            { if (!session.authenticated) conn.close(4001, "AUTH_TIMEOUT") },
            AUTH_TIMEOUT_S, TimeUnit.SECONDS
        )
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        sessions.remove(conn)
        if (activeClient === conn) {
            activeClient = null
            callbacks.onClientChanged(false)
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val session = sessions[conn] ?: return
        val json = try { JSONObject(message) } catch (_: JSONException) { return }
        if (!session.authenticated) authenticate(conn, session, json) else route(conn, json)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
        if (conn == null) callbacks.onFatalError(ex) // e.g. BindException
    }

    // ---- auth -------------------------------------------------------------------------------

    private fun authenticate(conn: WebSocket, session: Session, json: JSONObject) {
        val expected = Security.hmacHex(currentToken(), session.nonce)
        val ok = json.optString("type") == "auth" &&
            Security.constantTimeEquals(expected, json.optString("hmac"))

        if (!ok) {
            conn.close(4003, "AUTH_FAILED")
            if (authFailures.incrementAndGet() >= MAX_AUTH_FAILURES) {
                authFailures.set(0)
                callbacks.onTooManyAuthFailures() // service rotates the token => old QR is dead
            }
            return
        }

        authFailures.set(0)
        session.authenticated = true
        val previous = activeClient
        activeClient = conn                       // newest authenticated client wins
        if (previous != null && previous !== conn) previous.close(4002, "REPLACED")
        callbacks.onClientChanged(true)
        conn.send(
            JSONObject().put("type", "auth_ok").put("capabilities", dispatcher.capabilities()).toString()
        )
    }

    // ---- routing ----------------------------------------------------------------------------

    private fun route(conn: WebSocket, json: JSONObject) {
        val command = json.optString("command")
        when {
            json.optString("type") == "ping" -> conn.send("""{"type":"pong"}""")
            command.isNotEmpty() -> dispatcher.handleCommand(command)?.let { code ->
                conn.send(
                    JSONObject().put("type", "error").put("code", code).put("command", command).toString()
                )
            }
            json.has("x") && json.has("y") ->
                dispatcher.handleMouse(json.optDouble("x"), json.optDouble("y"), json.optString("mode", "rel"))
        }
    }

    // ---- lifecycle helpers ------------------------------------------------------------------

    /** Used when the token is rotated: every existing session is invalid. */
    fun disconnectAll() {
        for (c in connections.toList()) c.close(4004, "SESSION_RESET")
    }

    fun shutdown() {
        timeouts.shutdownNow()
        stop(300)
    }

    private companion object {
        const val TAG = "RemoteWsServer"
        const val AUTH_TIMEOUT_S = 10L
        const val MAX_AUTH_FAILURES = 5
        const val MAX_PENDING = 8
    }
}
