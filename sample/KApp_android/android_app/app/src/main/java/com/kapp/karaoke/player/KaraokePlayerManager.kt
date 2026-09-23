package com.kapp.karaoke.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.kapp.karaoke.audio.VocalRemovalAudioProcessor
import com.kapp.karaoke.model.PlayerState
import com.kapp.karaoke.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class KaraokePlayerManager(
    private val context: Context,
    private val onSongFinished: () -> Unit
) {
    val vocalProcessor = VocalRemovalAudioProcessor()
    private val mainHandler = Handler(Looper.getMainLooper())

    val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(vocalProcessor))
                    .build()
            }
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            addListener(playerEventListener)
            volume = 0.5f
        }
    }

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            mainHandler.postDelayed(this, 500)
        }
    }

    private val playerEventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    mainHandler.removeCallbacks(progressRunnable)
                    updateProgress()
                    onSongFinished()
                }
                Player.STATE_READY -> {
                    updateProgress()
                }
                else -> {}
            }
            updateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
            if (isPlaying) {
                mainHandler.post(progressRunnable)
            } else {
                mainHandler.removeCallbacks(progressRunnable)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("KaraokePlayer", "ExoPlayer playback error: ${error.message}", error)
            _playerState.value = _playerState.value.copy(isPlaying = false)
        }
    }

    /**
     * Plays a ready song. Thread-safe: dispatches to Main Looper if called from background thread.
     * Uses MergingMediaSource so adaptive video track and high-fidelity audio track play in perfect sync!
     */
    fun playSong(song: SongItem) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { playSong(song) }
            return
        }

        _currentSong.value = song

        val videoFile = if (song.videoPath.isNotBlank()) File(song.videoPath) else null
        val audioFile = if (song.audioPath.isNotBlank()) File(song.audioPath) else null

        val hasVideo = videoFile != null && videoFile.exists() && videoFile.length() > 10_000L
        val hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 10_000L

        if (!hasVideo && !hasAudio) {
            android.util.Log.e("KaraokePlayer", "Cannot play: no video or audio files available for ${song.title}")
            return
        }

        try {
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
            val progressiveSourceFactory = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)

            if (hasVideo && hasAudio) {
                // Merge video stream and audio stream for simultaneous video playback + karaoke audio
                val videoSource = progressiveSourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(videoFile!!)))
                val audioSource = progressiveSourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(audioFile!!)))
                val mergedSource = androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)
                exoPlayer.setMediaSource(mergedSource)
            } else if (hasVideo) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile!!))
                exoPlayer.setMediaItem(mediaItem)
            } else {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile!!))
                exoPlayer.setMediaItem(mediaItem)
            }

            exoPlayer.prepare()
            exoPlayer.play()

            updateState()
        } catch (e: Exception) {
            android.util.Log.e("KaraokePlayer", "Failed to start playback: ${e.message}", e)
        }
    }

    fun togglePlayPause() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { togglePlayPause() }
            return
        }
        try {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                exoPlayer.play()
            }
            updateState()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stop() }
            return
        }
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            mainHandler.removeCallbacks(progressRunnable)
            _playerState.value = _playerState.value.copy(
                isPlaying = false,
                positionMs = 0,
                durationMs = 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { seekTo(positionMs) }
            return
        }
        try {
            exoPlayer.seekTo(positionMs)
            updateProgress()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setVolume(volumePercent: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setVolume(volumePercent) }
            return
        }
        try {
            val clamped = volumePercent.coerceIn(0, 100)
            exoPlayer.volume = clamped / 100f
            _playerState.value = _playerState.value.copy(volume = clamped)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setVocals(vocalsOn: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setVocals(vocalsOn) }
            return
        }
        vocalProcessor.isVocalRemovalEnabled = !vocalsOn
        _playerState.value = _playerState.value.copy(isVocalsOn = vocalsOn)
    }

    fun toggleVocals() {
        setVocals(!_playerState.value.isVocalsOn)
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        mainHandler.removeCallbacks(progressRunnable)
        exoPlayer.removeListener(playerEventListener)
        exoPlayer.release()
    }

    private fun updateProgress() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            _playerState.value = _playerState.value.copy(
                positionMs = pos,
                durationMs = dur
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateState() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        try {
            _playerState.value = _playerState.value.copy(
                isPlaying = exoPlayer.isPlaying,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                durationMs = exoPlayer.duration.coerceAtLeast(0L),
                currentTitle = _currentSong.value?.title ?: "",
                currentSongId = _currentSong.value?.id
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
