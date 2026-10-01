package com.audio_arcade.arcadeshare.model

data class RoomCode(
    val code:       String,
    val createdAt:  Long = System.currentTimeMillis(),
    val guestCount: Int  = 0,
    val maxGuests:  Int  = 6
) {
    val isFull: Boolean get() = guestCount >= maxGuests
}