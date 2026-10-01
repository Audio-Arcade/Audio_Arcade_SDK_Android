package com.audio_arcade.arcadeshare.callback

import com.audio_arcade.arcadeshare.model.ShareState

interface GuestCallback {

    /** Room rejointe avec succès. lastState peut être null si l'hôte n'a pas encore joué. */
    fun onRoomJoined(code: String, hostName: String, guestCount: Int, lastState: ShareState?)

    /**
     * Nouvel état reçu depuis l'hôte.
     * audioUrl est non vide si le stream HTTP est disponible.
     */
    fun onStateReceived(state: ShareState)

    /** L'hôte a quitté la session. */
    fun onHostLeft()

    /** Erreur serveur ou réseau. */
    fun onError(message: String)

    /** Déconnexion du serveur (réseau perdu). */
    fun onDisconnected()
}