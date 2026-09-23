package com.kapp.karaoke.model

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 50,
    val isVocalsOn: Boolean = true,
    val currentTitle: String = "",
    val currentSongId: String? = null
) {
    val positionFormatted: String
        get() = formatTime(positionMs)

    val durationFormatted: String
        get() = formatTime(durationMs)

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }
}
