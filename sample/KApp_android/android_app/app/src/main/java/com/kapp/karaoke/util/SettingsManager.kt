package com.kapp.karaoke.util

import android.content.Context

object SettingsManager {
    private const val PREFS_NAME = "kapp_prefs"
    private const val KEY_VIDEO_QUALITY = "video_quality"

    // Supported YouTube video qualities: min 480p to max 4K
    val QUALITY_OPTIONS = listOf("480p", "720p", "1080p", "1440p", "4K")
    const val DEFAULT_QUALITY = "720p"

    fun getVideoQuality(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val q = prefs.getString(KEY_VIDEO_QUALITY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
        return if (q in QUALITY_OPTIONS) q else DEFAULT_QUALITY
    }

    fun setVideoQuality(context: Context, quality: String) {
        val q = if (quality in QUALITY_OPTIONS) quality else DEFAULT_QUALITY
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_VIDEO_QUALITY, q).apply()
    }

    fun getMaxHeightForQuality(quality: String?): Int {
        return when (quality?.trim()?.uppercase()) {
            "480P" -> 480
            "720P" -> 720
            "1080P" -> 1080
            "1440P", "2K" -> 1440
            "2160P", "4K" -> 2160
            else -> 720
        }
    }
}
