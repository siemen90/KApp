package com.kapp.karaoke.download

import com.kapp.karaoke.audio.AudioVocalRemover
import com.kapp.karaoke.model.SongItem
import com.kapp.karaoke.model.SongStatus
import com.kapp.karaoke.storage.StorageManager
import com.kapp.karaoke.youtube.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class SongDownloadManager(
    private val storageManager: StorageManager,
    private val youtubeExtractor: YouTubeExtractor
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads song from YouTube and places all related files into its own dedicated
     * folder inside the Download directory:
     * e.g., Download/KApp/<SongTitle>/
     *
     * Stages:
     * 1. 0% - 35%: Download Video stream (.mp4)
     * 2. 35% - 50%: Download Audio stream (.m4a)
     * 3. 50% - 95%: Remove Vocals (creates <SongTitle>_instrumental.wav)
     * 4. 100%: Ready for playback!
     */
    suspend fun downloadSong(
        songItem: SongItem,
        onProgress: (progress: Int, status: SongStatus) -> Unit,
        onTitleResolved: ((String) -> Unit)? = null
    ): SongItem = withContext(Dispatchers.IO) {
        try {
            onProgress(0, SongStatus.DOWNLOADING)

            val prefQuality = songItem.preferredQuality ?: com.kapp.karaoke.util.SettingsManager.getVideoQuality(storageManager.context)
            val targetHeight = com.kapp.karaoke.util.SettingsManager.getMaxHeightForQuality(prefQuality)
            val streamInfo = youtubeExtractor.extractStreamUrls(songItem.youtubeUrl, targetHeight = targetHeight)
                ?: throw IllegalStateException("Could not extract stream URLs from YouTube.")

            val isCorruptedTitle = songItem.title.isBlank() ||
                songItem.title == "YouTube Song" ||
                songItem.title.contains('\uFFFD') ||
                (songItem.title.length > 2 && songItem.title.all { it == '?' || it.isWhitespace() })

            val effectiveTitle = if (isCorruptedTitle && streamInfo.title.isNotBlank()) {
                streamInfo.title
            } else {
                songItem.title.ifBlank { streamInfo.title }
            }

            onTitleResolved?.invoke(effectiveTitle)

            val songFolder = storageManager.createSongFolder(effectiveTitle)

            val safeFilename = songFolder.name
            val videoExt = if (streamInfo.isVideoMp4) "mp4" else "webm"
            val videoFile = File(songFolder, "$safeFilename.$videoExt")
            val altVideoFile = File(songFolder, if (streamInfo.isVideoMp4) "$safeFilename.webm" else "$safeFilename.mp4")
            val actualVideoFile = if (altVideoFile.exists() && altVideoFile.length() > 50_000L) altVideoFile else videoFile

            val audioSourceFile = File(songFolder, "$safeFilename.m4a")
            val originalWavFile = File(songFolder, "$safeFilename.wav")
            val instWavFile = File(songFolder, "${safeFilename}_instrumental.wav")

            // Clean up any incomplete / 0-byte corrupt files from prior runs
            if (videoFile.exists() && videoFile.length() < 50_000L) videoFile.delete()
            if (altVideoFile.exists() && altVideoFile.length() < 50_000L) altVideoFile.delete()
            if (audioSourceFile.exists() && audioSourceFile.length() < 10_000L) audioSourceFile.delete()
            if (originalWavFile.exists() && originalWavFile.length() < 10_000L) originalWavFile.delete()
            if (instWavFile.exists() && instWavFile.length() < 10_000L) instWavFile.delete()

            val videoDownloadUrl = streamInfo.videoUrl ?: streamInfo.combinedUrl
            val audioDownloadUrl = streamInfo.audioUrl ?: streamInfo.combinedUrl

            // 1. Download Video Stream (0% - 35%)
            if (videoDownloadUrl != null && !actualVideoFile.exists()) {
                downloadStreamToFile(videoDownloadUrl, actualVideoFile) { percent ->
                    val overallProgress = (percent * 0.35f).toInt().coerceIn(1, 35)
                    onProgress(overallProgress, SongStatus.DOWNLOADING)
                }
            }
            onProgress(35, SongStatus.DOWNLOADING)

            // 2. Download Audio Stream (35% - 50%)
            val audioSource = if (audioDownloadUrl != null && !audioSourceFile.exists()) {
                downloadStreamToFile(audioDownloadUrl, audioSourceFile) { percent ->
                    val overallProgress = (35 + (percent * 0.15f)).toInt().coerceIn(36, 50)
                    onProgress(overallProgress, SongStatus.DOWNLOADING)
                }
                audioSourceFile
            } else if (audioSourceFile.exists()) {
                audioSourceFile
            } else {
                actualVideoFile
            }
            onProgress(50, SongStatus.DOWNLOADING)

            // 3. Vocal Removal Stage (50% - 95%)
            // Generates dedicated <title>_instrumental.wav and <title>.wav
            var vocalRemovalSucceeded = false
            if (audioSource.exists() && audioSource.length() > 0) {
                if (!originalWavFile.exists() || !instWavFile.exists()) {
                    vocalRemovalSucceeded = AudioVocalRemover.processAudio(
                        sourceFile = audioSource,
                        originalWavFile = originalWavFile,
                        instrumentalWavFile = instWavFile
                    ) { percent ->
                        val overallProgress = (50 + (percent * 0.45f)).toInt().coerceIn(51, 95)
                        onProgress(overallProgress, SongStatus.DOWNLOADING)
                    }
                } else {
                    vocalRemovalSucceeded = true
                }
            }

            // 4. Save metadata.json
            storageManager.saveSongMetadata(
                songFolder = songFolder,
                title = effectiveTitle,
                url = songItem.youtubeUrl,
                duration = streamInfo.durationSeconds,
                quality = prefQuality
            )

            val resolvedAudioPath = when {
                originalWavFile.exists() && originalWavFile.length() > 10_000L -> originalWavFile.absolutePath
                audioSourceFile.exists() && audioSourceFile.length() > 10_000L -> audioSourceFile.absolutePath
                actualVideoFile.exists() && actualVideoFile.length() > 10_000L -> actualVideoFile.absolutePath
                else -> throw IllegalStateException("Failed to download playable audio or video file.")
            }

            val resolvedInstrumentalPath = if (instWavFile.exists() && instWavFile.length() > 10_000L) {
                instWavFile.absolutePath
            } else ""

            onProgress(100, SongStatus.READY)

            // Refresh library list so newly downloaded song shows immediately in Library
            storageManager.refreshLocalSongs()

            songItem.copy(
                title = effectiveTitle,
                videoPath = if (actualVideoFile.exists() && actualVideoFile.length() > 10_000L) actualVideoFile.absolutePath else "",
                audioPath = resolvedAudioPath,
                instrumentalPath = resolvedInstrumentalPath,
                folderPath = songFolder.absolutePath,
                durationSeconds = streamInfo.durationSeconds,
                status = SongStatus.READY,
                downloadProgress = 100,
                isLocal = true,
                videoQuality = prefQuality
            )
        } catch (e: Exception) {
            android.util.Log.e("SongDownloadManager", "Download failed for ${songItem.title}: ${e.message}", e)
            onProgress(0, SongStatus.ERROR)
            songItem.copy(
                status = SongStatus.ERROR,
                errorMessage = e.localizedMessage ?: "Download failed"
            )
        }
    }

    private fun downloadStreamToFile(url: String, destination: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept-Encoding", "identity")
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code} downloading stream.")
        }
        val body = resp.body ?: throw IllegalStateException("Empty response body.")
        val contentLength = body.contentLength()

        var lastReportedPercent = -1
        var lastReportTime = 0L

        body.byteStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    val pct = if (contentLength > 0) {
                        ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                    } else {
                        // Fallback estimation for unknown content length
                        ((totalRead / (1024 * 1024)).toInt() * 5).coerceIn(1, 95)
                    }

                    if (pct != lastReportedPercent && (now - lastReportTime > 100 || pct == 100)) {
                        lastReportedPercent = pct
                        lastReportTime = now
                        onProgress(pct)
                    }
                }
            }
        }
    }
}
