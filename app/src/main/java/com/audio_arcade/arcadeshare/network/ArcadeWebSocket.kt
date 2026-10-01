package com.audio_arcade.arcadeshare.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audio_arcade.arcadeshare.ArcadeShare
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class ArcadeWebSocket(
    private val serverUrl:  String,
    private val onOpen:     () -> Unit,
    private val onMessage:  (String) -> Unit,
    private val onError:    (String) -> Unit,
    private val onClosed:   () -> Unit
) {
    private var ws:               WebSocket? = null
    private val handler           = Handler(Looper.getMainLooper())
    private val pending           = mutableListOf<String>()
    @Volatile private var isOpen        = false
    @Volatile private var authenticated = false
    private var shouldReconnect   = true
    private var reconnectAttempts = 0
    private val maxAttempts       = 5

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0,  TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isOpen && authenticated) {
                ws?.send("""{"type":"ping"}""")
                handler.postDelayed(this, 25_000)
            }
        }
    }

    fun connect() {
        shouldReconnect = true
        authenticated = false
        val req = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(req, listener)
    }

    /** Les messages sont mis en file tant que la connexion n'est pas authentifiée. */
    fun send(msg: String) {
        if (isOpen && authenticated) ws?.send(msg)
        else pending.add(msg)
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacks(pingRunnable)
        ws?.close(1000, "Déconnexion normale")
        ws = null
        isOpen = false
        authenticated = false
        pending.clear()
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            authenticated = false
            reconnectAttempts = 0
            // Handshake : doit être le tout premier message
            webSocket.send(JSONObject().apply {
                put("type", "auth")
                put("appId", ArcadeShare.appId)
                put("versionId", ArcadeShare.versionId)
            }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"pong\"")) return

            if (!authenticated) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                when (json?.optString("type")) {
                    "auth_ok" -> {
                        if (json.optBoolean("deprecated")) {
                            Log.w("ArcadeShare", "SDK déprécié : ${json.optString("reason")}")
                        }
                        handler.post {
                            authenticated = true
                            handler.removeCallbacks(pingRunnable)
                            onOpen()   // envoie create_room / rejoin_room / join_room
                            pending.forEach { webSocket.send(it) }
                            pending.clear()
                            handler.postDelayed(pingRunnable, 25_000)
                        }
                        return
                    }
                    "auth_rejected" -> {
                        val reason = json.optString("reason", "Application non autorisée")
                        handler.post {
                            shouldReconnect = false
                            onError(reason)
                        }
                        return
                    }
                }
            }
            handler.post { onMessage(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            authenticated = false
            handler.post {
                handler.removeCallbacks(pingRunnable)
                onClosed()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isOpen = false
            authenticated = false
            handler.post {
                handler.removeCallbacks(pingRunnable)
                if (shouldReconnect && reconnectAttempts < maxAttempts) {
                    reconnectAttempts++
                    val delay = (reconnectAttempts * 2000L).coerceAtMost(10_000L)
                    handler.postDelayed({ if (shouldReconnect) connect() }, delay)
                } else {
                    onError(t.message ?: "Erreur de connexion")
                }
            }
        }
    }
}