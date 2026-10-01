package com.audio_arcade.arcadeshare.callback

import com.audio_arcade.arcadeshare.model.RoomCode

interface HostCallback {

    /** Room créée avec succès — le code est prêt à être partagé. */
    fun onRoomCreated(room: RoomCode)

    /** Un invité a rejoint la room. */
    fun onGuestJoined(room: RoomCode, username: String)

    /** Un invité a quitté la room. */
    fun onGuestLeft(room: RoomCode, username: String)

    /** Erreur serveur ou réseau. */
    fun onError(message: String)

    /** Déconnexion du serveur (réseau perdu). */
    fun onDisconnected()
}