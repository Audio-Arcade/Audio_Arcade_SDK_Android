package com.audio_arcade.arcadeshare

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.audio_arcade.arcadeshare.callback.GuestCallback
import com.audio_arcade.arcadeshare.model.ShareState
import com.audio_arcade.arcadeshare.network.ArcadeWebSocket
import com.audio_arcade.arcadeshare.network.DeviceId
import org.json.JSONObject

class ArcadeShareGuest(
    private val context:   Context,
    private val serverUrl: String,
    private val httpUrl:   String,
    private val username:  String
) {
    private var callback:  GuestCallback?   = null
    private var ws:        ArcadeWebSocket? = null
    private var roomCode:  String           = ""
    private val handler    = Handler(Looper.getMainLooper())

    fun setCallback(cb: GuestCallback) { callback = cb }

    fun joinRoom(code: String) {
        roomCode = code.trim().uppercase()
        val serial = getSerial()

        ws = ArcadeWebSocket(
            serverUrl = serverUrl,
            onOpen    = {
                ws?.send("""{"type":"join_room","code":"$roomCode","username":"$username","deviceSerial":"$serial"}""")
            },
            onMessage = { raw -> handleMessage(raw) },
            onError   = { err -> callback?.onError(err) },
            onClosed  = { callback?.onDisconnected() }
        )
        ws?.connect()
    }

    fun leaveRoom() {
        if (roomCode.isNotEmpty()) {
            ws?.send("""{"type":"leave_room","code":"$roomCode"}""")
        }
        ws?.disconnect()
        ws = null
        roomCode = ""
    }

    fun release() {
        leaveRoom()
        callback = null
    }

    fun getStreamUrl(): String? {
        if (roomCode.isEmpty()) return null
        return "$httpUrl/music/$roomCode"
    }

    private fun handleMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return

        when (msg.optString("type")) {
            "room_joined" -> {
                val hostName   = msg.optString("hostName", "Hôte")
                val guestCount = msg.optInt("guestCount", 0)
                val lastState  = msg.optJSONObject("lastState")?.let { buildState(it) }
                callback?.onRoomJoined(roomCode, hostName, guestCount, lastState)
                lastState?.let { callback?.onStateReceived(it) }
            }

            "state" -> {
                val state = buildState(msg)
                callback?.onStateReceived(state)
            }

            "host_left" -> callback?.onHostLeft()

            "error" -> callback?.onError(msg.optString("message", "Erreur inconnue"))
        }
    }

    /** Parse le JSON reçu en ShareState, puis reconstruit audioUrl côté client. */
    private fun buildState(json: JSONObject): ShareState {
        val base = json.toShareState()
        return if (base.currentFile.isNotEmpty()) {
            base.copy(audioUrl = "$httpUrl/music/$roomCode")
        } else {
            base
        }
    }

    private fun getSerial(): String = DeviceId.get(context)
}

// ── Extension JSONObject → ShareState ─────────────────────────────────────────
internal fun JSONObject.toShareState(): ShareState {
    val serverTs = optLong("timestamp", 0L)
    val latency  = if (serverTs > 0) (System.currentTimeMillis() - serverTs).coerceAtLeast(0) else 0L

    // Le serveur envoie position/duration en SECONDES (voir ArcadeShareHost.sendStateToServer) → conversion en ms
    val positionMs = (optDouble("position", 0.0) * 1000).toLong()
    val durationMs = (optDouble("duration", 0.0) * 1000).toLong()

    return ShareState(
        title       = optString("title",       ""),
        artist      = optString("artist",      ""),
        artworkUrl  = optString("artworkUrl",  ""),
        audioUrl    = "",   // reconstruit dans buildState()
        currentFile = optString("currentFile", ""),
        position    = positionMs + latency,
        duration    = durationMs,
        isPlaying   = optBoolean("isPlaying", false),
        nextTitle   = optString("nextTitle",  ""),
        timestamp   = System.currentTimeMillis()
    )
}