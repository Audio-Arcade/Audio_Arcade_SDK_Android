package com.audio_arcade.arcadeshare

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.audio_arcade.arcadeshare.model.ShareState
import com.audio_arcade.arcadeshare.network.DeviceId
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ArcadeShare {

    private const val DEFAULT_SERVER_URL = "wss://rooms.audio-arcade.fr.eu.org/ws"
    private const val DEFAULT_HTTP_URL   = "https://rooms.audio-arcade.fr.eu.org"

    private var appContext:  Context? = null
    private var serverUrl:   String   = DEFAULT_SERVER_URL
    private var httpUrl:     String   = DEFAULT_HTTP_URL
    private var initialized: Boolean  = false

    private val activeHosts  = mutableListOf<ArcadeShareHost>()
    private val activeGuests = mutableListOf<ArcadeShareGuest>()
    private val mainHandler  = Handler(Looper.getMainLooper())

    // Dernier état/piste connus : rejoués sur tout host qui devient prêt
    private var lastState:    ShareState? = null
    private var lastTrackKey: String?     = null
    private var lastTrackUri: Uri?        = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val SDK_VERSION_ID = "49273058164029573816"
    private const val PREFS_NAME  = "arcadeshare_prefs"
    private const val KEY_SHARING = "sharing_enabled"

    var onSharingChanged: (() -> Unit)? = null

    /** Au moins une room est prête : les invités peuvent écouter. */
    val isSharing: Boolean get() = activeHosts.any { it.room != null }

    /** Connexion en cours : une room est en train de (re)démarrer. */
    val isConnecting: Boolean get() = activeHosts.any { it.isActive && it.room == null }

    /** Identifiant de l'appli (champ "ID" dans authorised.json). */
    internal var appId: String = ""
        private set
    internal val versionId: String get() = SDK_VERSION_ID

    /** Paramètres d'authentification pour les requêtes HTTP. */
    internal fun authQuery(): String =
        "appId=${Uri.encode(appId)}&versionId=${Uri.encode(versionId)}"

    fun init(context: Context, appId: String) {
        require(appId.isNotBlank()) { "appId requis (champ ID dans authorised.json)" }
        this.appId  = appId
        appContext  = context.applicationContext
        serverUrl   = DEFAULT_SERVER_URL
        httpUrl     = DEFAULT_HTTP_URL
        initialized = true
    }

    fun init(context: Context, appId: String, serverUrl: String, httpUrl: String) {
        init(context, appId)
        this.serverUrl = serverUrl.trimEnd('/')
        this.httpUrl   = httpUrl.trimEnd('/')
    }

    // ── Hôtes ─────────────────────────────────────────────────────────────────
    fun createHost(username: String): ArcadeShareHost {
        checkInit()
        val host = ArcadeShareHost(appContext!!, serverUrl, httpUrl, username)
        host.onRoomReady = { onHostReady(it) }
        host.onActivityChanged = { onSharingChanged?.invoke() }
        activeHosts.add(host)
        return host
    }

    /** Host de la room [code] (confirmée ou en cours de reconnexion), sinon null. */
    fun getHost(code: String): ArcadeShareHost? {
        val c = code.trim().uppercase()
        return activeHosts.firstOrNull { it.roomCode == c }
    }

    fun getHosts(): List<ArcadeShareHost> = activeHosts.toList()

    /** Rejoint une room existante ; ne fait rien si un host la gère déjà. */
    fun rejoinHost(username: String, code: String): ArcadeShareHost {
        getHost(code)?.let { it.resume(); return it }
        val host = createHost(username)
        host.rejoinRoom(code)
        return host
    }

    /** Réactive plusieurs rooms (idempotent : on peut l'appeler autant de fois que voulu). */
    fun restoreRooms(username: String, codes: List<String>) {
        if (!isSharingEnabled()) return
        checkInit()
        codes.forEach { rejoinHost(username, it) }
    }

    /** Supprime la room [code] côté serveur et retire son host. */
    /** Supprime la room : stoppe son host local puis DELETE côté serveur. */
    fun deleteRoom(code: String, onResult: (Boolean) -> Unit = {}) {
        checkInit()
        val c = code.trim().uppercase()
        getHost(c)?.let { it.release(); activeHosts.remove(it) }

        val request = Request.Builder()
            .url("$httpUrl/room/$c/${Uri.encode(getDeviceSerial())}?${authQuery()}")
            .delete()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                mainHandler.post { onResult(false) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val ok = response.isSuccessful || response.code == 404   // 404 = déjà supprimée
                response.close()
                mainHandler.post { onResult(ok) }
            }
        })
    }

    // ── Diffusion (appelée par le lecteur de l'appli) ─────────────────────────
    /** Diffuse l'état de lecture à toutes les rooms actives. */
    fun broadcastState(state: ShareState) {
        lastState = state
        activeHosts.forEach { it.updateState(state) }
    }

    /** Envoie la piste courante à toutes les rooms (upload dédupliqué par [key]). */
    fun shareTrack(key: String, uri: Uri) {
        lastTrackKey = key
        lastTrackUri = uri
        activeHosts.forEach { it.shareTrack(key, uri) }
    }

    /** Préférence persistée : le partage est-il voulu (restauré au lancement) ? */
    fun isSharingEnabled(): Boolean =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_SHARING, true) ?: true

    private fun setSharingEnabled(enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_SHARING, enabled)?.apply()
    }

    /** Active le partage : (re)connecte les rooms [codes]. */
    fun startSharing(username: String, codes: List<String>) {
        checkInit()
        setSharingEnabled(true)
        codes.forEach { rejoinHost(username, it) }
        onSharingChanged?.invoke()
    }

    /** Coupe le partage (les rooms sont conservées, les invités sont déconnectés). */
    fun stopSharing() {
        setSharingEnabled(false)
        activeHosts.forEach { it.stopBroadcasting() }
        onSharingChanged?.invoke()
    }

    private fun onHostReady(host: ArcadeShareHost) {
        setSharingEnabled(true)
        val key = lastTrackKey
        val uri = lastTrackUri
        if (key != null && uri != null) host.shareTrack(key, uri)
        lastState?.let { host.updateState(it) }
    }

    // ── Invités ───────────────────────────────────────────────────────────────
    fun createGuest(username: String): ArcadeShareGuest {
        checkInit()
        val guest = ArcadeShareGuest(appContext!!, serverUrl, httpUrl, username)
        activeGuests.add(guest)
        return guest
    }

    /** Identifiant stable de l'appareil (équivalent GetDeviceSerial() côté C#). */
    fun getDeviceSerial(): String {
        checkInit()
        return DeviceId.get(appContext!!)
    }

    /**
     * Récupère les rooms permanentes créées par cet appareil.
     * Le callback est TOUJOURS rappelé sur le thread principal.
     */
    fun fetchMyRooms(onResult: (List<JSONObject>) -> Unit) {
        checkInit()
        val request = Request.Builder()
            .url("$httpUrl/my-rooms/${Uri.encode(getDeviceSerial())}?${authQuery()}")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                mainHandler.post { onResult(emptyList()) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val list = mutableListOf<JSONObject>()
                try {
                    val arr = JSONArray(response.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                } catch (_: Exception) {
                    list.clear()
                } finally {
                    response.close()
                }
                mainHandler.post { onResult(list) }
            }
        })
    }

    /** Coupe les connexions (les rooms sont conservées côté serveur). */
    fun release() {
        android.util.Log.w("ArcadeShare", "ArcadeShare.release() appelé", Throwable("appelant"))
        activeHosts.forEach  { it.release() }
        activeGuests.forEach { it.release() }
        activeHosts.clear()
        activeGuests.clear()
    }

    fun getServerUrl(): String = serverUrl
    fun getHttpUrl(): String = httpUrl
    fun version(): String = "1.0.0"

    private fun checkInit() {
        check(initialized) {
            "ArcadeShare n'est pas initialisé. Appelle ArcadeShare.init() d'abord."
        }
    }
}