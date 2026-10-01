package com.audio_arcade.arcadeshare

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import com.audio_arcade.arcadeshare.callback.HostCallback
import com.audio_arcade.arcadeshare.model.RoomCode
import com.audio_arcade.arcadeshare.model.ShareState
import com.audio_arcade.arcadeshare.network.ArcadeWebSocket
import com.audio_arcade.arcadeshare.network.DeviceId
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ArcadeShareHost(
    private val context:   Context,
    private val serverUrl: String,   // wss://...
    private val httpUrl:   String,   // https://...
    private val username:  String
) {
    private var callback:      HostCallback?    = null
    private var ws:            ArcadeWebSocket? = null
    private var currentRoom:   RoomCode?        = null
    private var requestedCode: String?          = null   // code demandé via rejoinRoom()
    private val handler        = Handler(Looper.getMainLooper())
    private val deviceSerial   by lazy { DeviceId.get(context) }

    /** Appelé par ArcadeShare quand la room est prête (créée ou rejointe). */
    internal var onRoomReady: ((ArcadeShareHost) -> Unit)? = null

    /** Room courante (null tant qu'elle n'est pas confirmée par le serveur). */
    val room: RoomCode? get() = currentRoom

    /** Code de la room de ce host (confirmé, ou demandé si on est en train de rejoindre). */
    val roomCode: String? get() = currentRoom?.code ?: requestedCode

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── État de lecture ───────────────────────────────────────────────────────
    private var lastState:   ShareState? = null
    private var lastStateAt: Long        = 0L
    private var socketGen = 0

    val isActive: Boolean get() = ws != null

    internal var onActivityChanged: (() -> Unit)? = null

    private val broadcastRunnable = object : Runnable {
        override fun run() {
            val room = currentRoom ?: return
            lastState?.let { sendStateToServer(estimate(it), room.code) }
            handler.postDelayed(this, 2000)
        }
    }

    /** Avance la position depuis le dernier updateState() pour que le re-broadcast reste juste. */
    private fun estimate(state: ShareState): ShareState {
        if (!state.isPlaying) return state
        val pos = state.position + (SystemClock.elapsedRealtime() - lastStateAt)
        return state.copy(
            position = if (state.duration > 0) pos.coerceAtMost(state.duration) else pos
        )
    }

    // ── Upload de la piste ────────────────────────────────────────────────────
    private var uploadedKey:   String?              = null
    private var uploadingKey:  String?              = null
    private var pendingTrack:  Pair<String, Uri>?   = null

    fun setCallback(cb: HostCallback?) { callback = cb }

    // ── Connexion ─────────────────────────────────────────────────────────────
    fun createRoom() {
        requestedCode = null
        openSocket()
    }

    fun rejoinRoom(code: String) {
        requestedCode = code.trim().uppercase()
        openSocket()
    }

    /**
     * Sur chaque (re)connexion : si on connaît déjà le code, on rejoint la room
     * au lieu d'en créer une nouvelle (sinon une reconnexion réseau changerait le code).
     */
    private fun openSocket() {
        ws?.disconnect()
        val gen = ++socketGen
        ws = ArcadeWebSocket(
            serverUrl = serverUrl,
            onOpen    = {
                if (gen == socketGen) {
                    val code = currentRoom?.code ?: requestedCode
                    ws?.send(
                        if (code == null) authMessage("create_room")
                        else authMessage("rejoin_room", code)
                    )
                }
            },
            onMessage = { raw -> if (gen == socketGen) handleMessage(raw) },
            onError   = { err ->
                if (gen == socketGen) {
                    markInactive()
                    callback?.onError(err)
                }
            },
            onClosed  = {
                if (gen == socketGen) {
                    markInactive()
                    callback?.onDisconnected()
                }
            }
        )
        ws?.connect()
    }

    private fun authMessage(type: String, code: String? = null): String =
        JSONObject().apply {
            put("type", type)
            if (code != null) put("code", code)
            put("username", username)
            put("deviceSerial", deviceSerial)
        }.toString()

    // ── Diffusion de l'état ───────────────────────────────────────────────────
    /** Met à jour l'état de lecture et le diffuse aux invités (sans ré-upload). */
    fun updateState(state: ShareState) {
        lastState   = state
        lastStateAt = SystemClock.elapsedRealtime()
        currentRoom?.code?.let { sendStateToServer(state, it) }
    }

    /**
     * Upload la piste [key] (ex: mediaId) si elle n'a pas déjà été envoyée à cette room.
     * Les uploads sont sérialisés : si une piste change pendant un upload, la dernière gagne.
     */
    fun shareTrack(key: String, uri: Uri) {
        if (currentRoom == null) return
        if (key == uploadedKey) return
        if (uploadingKey != null) {
            pendingTrack = key to uri
            return
        }
        uploadingKey = key
        uploadMusic(uri) { ok ->
            uploadingKey = null
            if (ok) uploadedKey = key
            pendingTrack?.let { (k, u) ->
                pendingTrack = null
                shareTrack(k, u)
            }
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────
    fun uploadMusic(file: File, onResult: (Boolean) -> Unit) =
        uploadMusic(Uri.fromFile(file), onResult)

    /** Upload en streaming depuis un content:// ou file:// (pas de copie en cache). */
    fun uploadMusic(uri: Uri, onResult: (Boolean) -> Unit) {
        val room = currentRoom ?: return onResult(false)

        val (name, size) = queryMeta(uri)
        val mime = context.contentResolver.getType(uri) ?: getMimeTypeForFile(name)

        val fileBody = object : RequestBody() {
            override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
            override fun contentLength(): Long = size
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
            }
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("music", name, fileBody)
            .build()

        val request = Request.Builder()
            .url("$httpUrl/upload-music")
            .addHeader("x-room-code", room.code)
            .addHeader("x-device-serial", deviceSerial)
            .addHeader("x-app-id", ArcadeShare.appId)
            .addHeader("x-version-id", ArcadeShare.versionId)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                android.util.Log.e("ArcadeShare", "Upload failure: ${e.message}", e)
                handler.post { onResult(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string()
                android.util.Log.d("ArcadeShare", "Upload response: code=${response.code} body=$respBody")
                handler.post { onResult(response.isSuccessful) }
                response.close()
            }
        })
    }

    fun resume() {
        if (ws != null) return
        roomCode?.let { rejoinRoom(it) }
    }

    private fun markInactive() {
        socketGen++
        handler.removeCallbacks(broadcastRunnable)
        requestedCode = roomCode
        currentRoom = null
        ws?.disconnect()
        ws = null
        onActivityChanged?.invoke()
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } else {
            uri.path?.let { size = File(it).length() }
        }
        return (name ?: uri.lastPathSegment ?: "track") to size
    }

    private fun getMimeTypeForFile(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3"  -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav"  -> "audio/wav"
            "ogg"  -> "audio/ogg"
            "aac"  -> "audio/aac"
            "m4a"  -> "audio/mp4"
            "opus" -> "audio/opus"
            else   -> "application/octet-stream"
        }
    }

    // ── Fin de session ────────────────────────────────────────────────────────
    /** Suppression réelle de la room côté serveur. */
    fun deleteRoom() {
        android.util.Log.w("ArcadeShare", "deleteRoom() appelé (room=$roomCode)", Throwable("appelant"))
        handler.removeCallbacks(broadcastRunnable)
        if (roomCode != null) ws?.send("""{"type":"delete_room"}""")
        currentRoom   = null
        requestedCode = null
        ws?.disconnect()
        ws = null
    }

    /** Quitte la room SANS la supprimer (elle pourra être rejointe via rejoinRoom). */
    fun stopBroadcasting() {
        ws?.send("""{"type":"leave_room"}""")
        markInactive()
    }

    fun release() {
        stopBroadcasting()
        callback          = null
        onRoomReady       = null
        onActivityChanged = null
    }

    private fun sendStateToServer(state: ShareState, code: String) {
        val json = JSONObject().apply {
            put("type",       "state")
            put("code",       code)
            put("position",   state.position / 1000.0)   // le serveur attend des secondes
            put("isPlaying",  state.isPlaying)
            put("title",      state.title)
            put("artist",     state.artist)
            put("artworkUrl", state.artworkUrl)
            put("duration",   state.duration / 1000.0)
            put("nextTitle",  state.nextTitle)
        }
        ws?.send(json.toString())
    }

    private fun handleMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return

        when (msg.optString("type")) {
            "room_created", "room_rejoined" -> {
                val code = msg.optString("code", requestedCode ?: "")
                if (code.isEmpty()) return
                currentRoom = RoomCode(code = code, guestCount = msg.optInt("guestCount", 0))
                uploadedKey = null   // on ne sait pas ce que le serveur a gardé → on renvoie la piste courante
                callback?.onRoomCreated(currentRoom!!)
                handler.removeCallbacks(broadcastRunnable)
                handler.postDelayed(broadcastRunnable, 2000)
                onRoomReady?.invoke(this)
            }
            "guest_joined" -> {
                val count = msg.optInt("guestCount", 0)
                val uname = msg.optString("username", "Inconnu")
                currentRoom = currentRoom?.copy(guestCount = count)
                currentRoom?.let { callback?.onGuestJoined(it, uname) }
            }
            "guest_left" -> {
                val count = msg.optInt("guestCount", 0)
                val uname = msg.optString("username", "Inconnu")
                currentRoom = currentRoom?.copy(guestCount = count)
                currentRoom?.let { callback?.onGuestLeft(it, uname) }
            }
            "error" -> callback?.onError(msg.optString("message", "Erreur inconnue"))
        }
    }
}