package com.kapp.karaoke.model

import java.util.UUID

enum class SongStatus {
    WAITING,
    DOWNLOADING,
    READY,
    PLAYING,
    PLAYED,
    ERROR
}

data class SongItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val youtubeUrl: String = "",
    val videoPath: String = "",
    val audioPath: String = "",
    val instrumentalPath: String = "",
    val folderPath: String = "",
    val durationSeconds: Long = 0,
    val status: SongStatus = SongStatus.WAITING,
    val downloadProgress: Int = 0,
    val isLocal: Boolean = false,
    val errorMessage: String? = null,
    val preferredQuality: String? = null,
    val videoQuality: String? = null
) {
    val durationFormatted: String
        get() {
            if (durationSeconds <= 0) return ""
            val m = durationSeconds / 60
            val s = durationSeconds % 60
            return String.format("%d:%02d", m, s)
        }
}
