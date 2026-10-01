package com.audio_arcade.arcadeshare.model

data class ShareState(
    val title:       String = "",
    val artist:      String = "",
    val artworkUrl:  String = "",   // URI Android (content://) ou URL HTTP stream
    val audioUrl:    String = "",   // URL HTTP du stream audio courant (construit côté client)
    val currentFile: String = "",   // Nom du fichier courant côté serveur (sert à détecter un changement de piste)
    val position:    Long   = 0L,   // position en ms
    val duration:    Long   = 0L,   // durée totale en ms
    val isPlaying:   Boolean = false,
    val nextTitle:   String = "",
    val timestamp:   Long   = 0L    // System.currentTimeMillis() côté serveur
)