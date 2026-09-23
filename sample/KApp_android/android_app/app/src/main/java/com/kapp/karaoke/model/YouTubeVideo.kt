package com.kapp.karaoke.model

data class YouTubeVideo(
    val id: String,
    val title: String,
    val url: String,
    val durationSeconds: Long = 0,
    val durationText: String = "",
    val uploader: String = "",
    val thumbnailUrl: String = ""
)
