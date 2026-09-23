package com.kapp.karaoke.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kapp.karaoke.model.SongItem
import com.kapp.karaoke.model.SongStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class StorageManager(val context: Context) {

    private val gson = Gson()
    private val qualityCache = ConcurrentHashMap<String, String>()
    private val durationCache = ConcurrentHashMap<String, Long>()

    fun resolveVideoInfo(videoPath: String, metaQuality: String?, metaDuration: Long): Pair<String, Long> {
        var quality = metaQuality?.trim().orEmpty()
        var duration = if (metaDuration > 0) metaDuration else 0L

        if (videoPath.isBlank()) {
            return Pair(quality, duration)
        }

        if (quality.isEmpty() && qualityCache.containsKey(videoPath)) {
            quality = qualityCache[videoPath].orEmpty()
        }
        if (duration <= 0L && durationCache.containsKey(videoPath)) {
            duration = durationCache[videoPath] ?: 0L
        }

        if (quality.isNotEmpty() && duration > 0L) {
            return Pair(quality, duration)
        }

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoPath)
            if (quality.isEmpty()) {
                val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = hStr?.toIntOrNull() ?: 0
                val w = wStr?.toIntOrNull() ?: 0
                val minDim = if (h > 0 && w > 0) minOf(h, w) else maxOf(h, w)
                val maxDim = maxOf(h, w)
                quality = when {
                    minDim >= 2000 || maxDim >= 3800 -> "4K"
                    minDim >= 1400 || maxDim >= 2500 -> "1440p"
                    minDim >= 1000 || maxDim >= 1900 -> "1080p"
                    minDim >= 700 || maxDim >= 1200 -> "720p"
                    minDim >= 460 || maxDim >= 800 -> "480p"
                    minDim >= 340 -> "360p"
                    minDim > 0 -> "${minDim}p"
                    else -> ""
                }
                if (quality.isNotEmpty()) {
                    qualityCache[videoPath] = quality
                }
            }
            if (duration <= 0L) {
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durMs = durStr?.toLongOrNull() ?: 0L
                if (durMs > 0) {
                    duration = durMs / 1000
                    durationCache[videoPath] = duration
                }
            }
            retriever.release()
        } catch (e: Exception) {
            // ignore probing failures
        }

        return Pair(quality, duration)
    }

    fun resolveVideoQuality(videoPath: String, metaQuality: String?): String {
        return resolveVideoInfo(videoPath, metaQuality, 0L).first
    }

    /**
     * Root download folder for KApp:
     * Attempts to use public Download/KApp, with fallback to external files dir.
     */
    fun getKAppDownloadDir(): File {
        return try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(publicDownloads, "KApp")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // Test if we can write a tiny probe file
            val probe = File(dir, ".probe_${System.currentTimeMillis()}")
            if (probe.createNewFile()) {
                probe.delete()
                dir
            } else {
                getAppSpecificDownloadDir()
            }
        } catch (e: Exception) {
            getAppSpecificDownloadDir()
        }
    }

    private fun getAppSpecificDownloadDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KApp")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Creates dedicated song folder:
     * Download/KApp/<SongTitle>/
     */
    fun createSongFolder(title: String): File {
        val safeTitle = sanitizeFilename(title)
        val parent = getKAppDownloadDir()
        val songDir = File(parent, safeTitle)
        if (!songDir.exists()) {
            val success = songDir.mkdirs()
            if (!success && !songDir.exists()) {
                // Fallback to app-specific folder if public failed
                val appSpecific = File(getAppSpecificDownloadDir(), safeTitle)
                appSpecific.mkdirs()
                return appSpecific
            }
        }
        return songDir
    }

    private val _localSongs = kotlinx.coroutines.flow.MutableStateFlow<List<SongItem>>(emptyList())
    val localSongs: kotlinx.coroutines.flow.StateFlow<List<SongItem>> = _localSongs.asStateFlow()

    fun refreshLocalSongs(): List<SongItem> {
        val list = scanLocalSongs()
        _localSongs.value = list
        return list
    }

    /**
     * Scans both public Download/KApp and app-specific Download/KApp
     */
    fun scanLocalSongs(): List<SongItem> {
        val songs = mutableListOf<SongItem>()
        val dirsToScan = listOf(getKAppDownloadDir(), getAppSpecificDownloadDir()).distinctBy { it.absolutePath }

        for (root in dirsToScan) {
            if (!root.exists() || !root.isDirectory) continue
            val folders = root.listFiles { file -> file.isDirectory } ?: continue

            for (folder in folders) {
                var title = folder.name
                var videoPath = ""
                var audioPath = ""
                var instPath = ""
                var duration = 0L
                var quality = ""

                val files = folder.listFiles() ?: continue
                for (file in files) {
                    val name = file.name.lowercase()
                    when {
                        name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") -> {
                            videoPath = file.absolutePath
                        }
                        name.endsWith("_instrumental.wav") || name.endsWith("_instrumental.mp3") || name.endsWith("_instrumental.m4a") -> {
                            instPath = file.absolutePath
                        }
                        name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".mp3") -> {
                            if (!name.contains("instrumental") && !name.contains("vocals")) {
                                audioPath = file.absolutePath
                            }
                        }
                        name == "metadata.json" -> {
                            try {
                                val json = file.readText(Charsets.UTF_8)
                                val type = object : TypeToken<Map<String, Any>>() {}.type
                                val meta: Map<String, Any> = gson.fromJson(json, type)
                                duration = (meta["duration"] as? Number)?.toLong() ?: 0L
                                val metaTitle = meta["title"]?.toString()?.trim()
                                if (!metaTitle.isNullOrBlank() && !metaTitle.contains('\uFFFD')) {
                                    title = metaTitle
                                }
                                val q = meta["quality"]?.toString()?.trim()
                                if (!q.isNullOrBlank()) {
                                    quality = q
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }

                if (videoPath.isNotEmpty() || audioPath.isNotEmpty()) {
                    val targetFile = videoPath.ifEmpty { audioPath }
                    val (resolvedQuality, resolvedDuration) = resolveVideoInfo(targetFile, quality, duration)
                    songs.add(
                        SongItem(
                            id = folder.absolutePath.hashCode().toString(),
                            title = title,
                            videoPath = videoPath,
                            audioPath = audioPath.ifEmpty { videoPath },
                            instrumentalPath = instPath,
                            folderPath = folder.absolutePath,
                            durationSeconds = resolvedDuration,
                            status = SongStatus.READY,
                            downloadProgress = 100,
                            isLocal = true,
                            videoQuality = resolvedQuality.ifEmpty { null }
                        )
                    )
                }
            }

            // Also check loose media files placed directly in Download/KApp
            val looseFiles = root.listFiles { file -> file.isFile } ?: emptyArray()
            for (file in looseFiles) {
                val name = file.name.lowercase()
                if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")) {
                    if (songs.none { it.videoPath == file.absolutePath }) {
                        val (resolvedQuality, resolvedDuration) = resolveVideoInfo(file.absolutePath, null, 0L)
                        songs.add(
                            SongItem(
                                id = file.absolutePath.hashCode().toString(),
                                title = file.nameWithoutExtension,
                                videoPath = file.absolutePath,
                                audioPath = file.absolutePath,
                                instrumentalPath = "",
                                folderPath = file.parent ?: "",
                                durationSeconds = resolvedDuration,
                                status = SongStatus.READY,
                                downloadProgress = 100,
                                isLocal = true,
                                videoQuality = resolvedQuality.ifEmpty { null }
                            )
                        )
                    }
                }
            }
        }

        return songs.distinctBy { it.folderPath + "_" + it.title }.sortedBy { it.title.lowercase() }
    }

    fun saveSongMetadata(songFolder: File, title: String, url: String, duration: Long, quality: String? = null) {
        try {
            val metaFile = File(songFolder, "metadata.json")
            val meta = mutableMapOf<String, Any>(
                "title" to title,
                "url" to url,
                "duration" to duration,
                "createdAt" to System.currentTimeMillis()
            )
            if (!quality.isNullOrBlank()) {
                meta["quality"] = quality
            }
            metaFile.writeText(gson.toJson(meta))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteSongFolder(folderPath: String): Boolean {
        if (folderPath.isBlank()) return false
        val folder = File(folderPath)
        return if (folder.exists() && folder.isDirectory) {
            folder.deleteRecursively()
        } else false
    }

    fun getFormattedStorageSpace(): String {
        return try {
            val dir = getKAppDownloadDir()
            val stat = StatFs(dir.path)
            val availableBytes = stat.availableBytes
            val totalBytes = stat.totalBytes
            if (totalBytes <= 0L) return ""

            val freeGb = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

            val freeStr = if (freeGb >= 1.0) {
                String.format(java.util.Locale.US, "%.1f", freeGb)
            } else {
                val freeMb = availableBytes.toDouble() / (1024.0 * 1024.0)
                String.format(java.util.Locale.US, "%.0fMB", freeMb)
            }

            val totalStr = if (totalGb >= 1.0) {
                if (Math.abs(totalGb - Math.round(totalGb)) < 0.2) {
                    "${Math.round(totalGb)}GB"
                } else {
                    String.format(java.util.Locale.US, "%.1fGB", totalGb)
                }
            } else {
                val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)
                String.format(java.util.Locale.US, "%.0fMB", totalMb)
            }

            "$freeStr / $totalStr"
        } catch (e: Exception) {
            ""
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .take(80)
            .ifEmpty { "Song_${System.currentTimeMillis()}" }
    }
}
