package com.kapp.karaoke.player

import com.kapp.karaoke.download.SongDownloadManager
import com.kapp.karaoke.model.SongItem
import com.kapp.karaoke.model.SongStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QueueManager(
    private val playerManager: KaraokePlayerManager,
    private val downloadManager: SongDownloadManager,
    private val scope: CoroutineScope
) {
    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    var currentPlayingIndex: Int = -1
        private set

    /**
     * Adds YouTube song to queue and starts download in background.
     * Does NOT force play until user plays or download is finished and player is idle.
     */
    fun addYouTubeSong(title: String, url: String, preferredQuality: String? = null) {
        val isCorrupted = title.isBlank() || title == "YouTube Song" || title.contains('\uFFFD') ||
                (title.length > 2 && title.all { it == '?' || it.isWhitespace() })
        val initialTitle = if (isCorrupted) "YouTube Song" else title.trim()

        val newItem = SongItem(
            title = initialTitle,
            youtubeUrl = url,
            status = SongStatus.WAITING,
            downloadProgress = 0,
            isLocal = false,
            preferredQuality = preferredQuality
        )

        val currentList = _queue.value.toMutableList()
        currentList.add(newItem)
        _queue.value = currentList

        // Download in background
        scope.launch(Dispatchers.IO) {
            val updated = downloadManager.downloadSong(
                newItem,
                onProgress = { progress, status ->
                    updateItemInQueue(newItem.id) { it.copy(downloadProgress = progress, status = status) }
                },
                onTitleResolved = { resolvedTitle ->
                    updateItemInQueue(newItem.id) { it.copy(title = resolvedTitle) }
                }
            )
            updateItemInQueue(newItem.id) { updated }

            // If player was idle and nothing is playing, and this song finished successfully, auto-play it
            if (updated.status == SongStatus.READY) {
                withContext(Dispatchers.Main) {
                    val currentPlaying = _queue.value.getOrNull(currentPlayingIndex)
                    if (currentPlaying == null || currentPlaying.status != SongStatus.PLAYING) {
                        playNext()
                    }
                }
            }
        }
    }

    /**
     * Adds already downloaded local song to queue.
     */
    fun addLocalSong(song: SongItem) {
        val readySong = song.copy(
            status = SongStatus.READY,
            downloadProgress = 100,
            isLocal = true
        )
        val currentList = _queue.value.toMutableList()
        currentList.add(readySong)
        _queue.value = currentList

        val currentPlaying = _queue.value.getOrNull(currentPlayingIndex)
        if (currentPlaying == null || currentPlaying.status != SongStatus.PLAYING) {
            playNext()
        }
    }

    /**
     * Plays a local song immediately.
     * If already in queue, switches to it. Otherwise inserts and plays it.
     */
    fun playLocalSongNow(song: SongItem) {
        val list = _queue.value.toMutableList()
        val existingIndex = list.indexOfFirst {
            (it.videoPath.isNotBlank() && it.videoPath == song.videoPath) ||
            (it.folderPath.isNotBlank() && it.folderPath == song.folderPath)
        }
        if (existingIndex != -1) {
            playSongAtIndex(existingIndex)
            return
        }

        for (i in list.indices) {
            if (list[i].status == SongStatus.PLAYING) {
                list[i] = list[i].copy(status = SongStatus.PLAYED)
            }
        }
        val readySong = song.copy(
            status = SongStatus.PLAYING,
            downloadProgress = 100,
            isLocal = true
        )
        val insertIndex = if (currentPlayingIndex in list.indices) currentPlayingIndex + 1 else list.size
        list.add(insertIndex, readySong)
        currentPlayingIndex = insertIndex
        _queue.value = list
        playerManager.playSong(readySong)
    }

    fun playSongAtIndex(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return

        val target = list[index]
        // Can only play ready songs or played songs (replay)
        if (target.status != SongStatus.READY && target.status != SongStatus.PLAYED && target.status != SongStatus.PLAYING) return

        // Mark ALL other playing songs in queue as played (prevents multiple playing bug)
        for (i in list.indices) {
            if (i != index && list[i].status == SongStatus.PLAYING) {
                list[i] = list[i].copy(status = SongStatus.PLAYED)
            }
        }

        currentPlayingIndex = index
        list[index] = target.copy(status = SongStatus.PLAYING)
        _queue.value = list

        playerManager.playSong(target)
    }

    /**
     * Stops playback and ensures any PLAYING song in queue is marked as PLAYED.
     */
    fun stop() {
        val list = _queue.value.toMutableList()
        for (i in list.indices) {
            if (list[i].status == SongStatus.PLAYING) {
                list[i] = list[i].copy(status = SongStatus.PLAYED)
            }
        }
        currentPlayingIndex = -1
        _queue.value = list
        playerManager.stop()
    }

    /**
     * Clears all songs marked as PLAYED from the queue.
     */
    fun clearPlayedSongs() {
        val list = _queue.value.filter { it.status != SongStatus.PLAYED }
        currentPlayingIndex = list.indexOfFirst { it.status == SongStatus.PLAYING }
        _queue.value = list
    }

    /**
     * Finds next READY song in the queue and starts playback.
     */
    fun playNext() {
        val list = _queue.value.toMutableList()
        // Ensure all currently playing songs are marked as PLAYED
        for (i in list.indices) {
            if (list[i].status == SongStatus.PLAYING) {
                list[i] = list[i].copy(status = SongStatus.PLAYED)
            }
        }
        _queue.value = list

        // Find next READY song after currentPlayingIndex
        var nextIdx = -1
        for (i in (currentPlayingIndex + 1) until list.size) {
            if (list[i].status == SongStatus.READY) {
                nextIdx = i
                break
            }
        }

        // If not found after current, check from beginning for any READY song
        if (nextIdx == -1) {
            for (i in list.indices) {
                if (list[i].status == SongStatus.READY) {
                    nextIdx = i
                    break
                }
            }
        }

        if (nextIdx != -1) {
            playSongAtIndex(nextIdx)
        } else {
            currentPlayingIndex = -1
            playerManager.stop()
        }
    }

    fun moveToNext(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices || list.size <= 1) return

        val playingIndex = list.indexOfFirst { it.status == SongStatus.PLAYING }.let {
            if (it >= 0) it else currentPlayingIndex
        }

        if (index == playingIndex) return

        val item = list.removeAt(index)

        val targetInsertIndex = if (playingIndex >= 0) {
            val adjustedPlayingIndex = if (index < playingIndex) playingIndex - 1 else playingIndex
            adjustedPlayingIndex + 1
        } else {
            0
        }

        val finalInsert = targetInsertIndex.coerceIn(0, list.size)
        list.add(finalInsert, item)

        currentPlayingIndex = list.indexOfFirst { it.status == SongStatus.PLAYING }
        _queue.value = list
    }

    fun moveUp(index: Int) {
        if (index <= 0) return
        val list = _queue.value.toMutableList()
        val item = list.removeAt(index)
        list.add(index - 1, item)
        currentPlayingIndex = list.indexOfFirst { it.status == SongStatus.PLAYING }
        _queue.value = list
    }

    fun moveDown(index: Int) {
        val list = _queue.value.toMutableList()
        if (index >= list.size - 1) return
        val item = list.removeAt(index)
        list.add(index + 1, item)
        currentPlayingIndex = list.indexOfFirst { it.status == SongStatus.PLAYING }
        _queue.value = list
    }

    fun deleteSong(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return

        val target = list[index]
        val wasPlaying = (index == currentPlayingIndex) || 
                         (target.status == SongStatus.PLAYING) || 
                         (playerManager.currentSong.value?.id == target.id)
        list.removeAt(index)

        if (wasPlaying) {
            currentPlayingIndex = -1
            playerManager.stop()
        } else if (index < currentPlayingIndex) {
            currentPlayingIndex--
        }

        _queue.value = list

        if (wasPlaying) {
            playNext()
        }
    }

    fun deleteSongById(id: String): Boolean {
        val list = _queue.value
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            deleteSong(index)
            return true
        }
        return false
    }

    fun replay(index: Int) {
        playSongAtIndex(index)
    }

    /**
     * Converts a previously played song back to unplayed (READY) status so it can be sung again.
     */
    fun markAsUnplayed(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        val item = list[index]
        if (item.status == SongStatus.PLAYED) {
            list[index] = item.copy(status = SongStatus.READY)
            _queue.value = list

            val currentPlaying = list.getOrNull(currentPlayingIndex)
            if (currentPlaying == null || currentPlaying.status != SongStatus.PLAYING) {
                playNext()
            }
        }
    }

    /**
     * Removes song from queue if present.
     * If the song being removed is currently playing, stops playback and moves to next song.
     */
    fun removeSongByFolderOrPath(folderPath: String, videoPath: String = "") {
        val currentSong = playerManager.currentSong.value
        val isCurrentlyPlaying = currentSong != null && 
            ((folderPath.isNotBlank() && currentSong.folderPath == folderPath) || 
             (videoPath.isNotBlank() && currentSong.videoPath == videoPath) || 
             (videoPath.isNotBlank() && currentSong.audioPath == videoPath))

        if (isCurrentlyPlaying) {
            playerManager.stop()
        }

        val list = _queue.value.toMutableList()
        val removeIndices = list.indices.filter {
            (folderPath.isNotBlank() && list[it].folderPath == folderPath) || 
            (videoPath.isNotBlank() && list[it].videoPath == videoPath) || 
            (videoPath.isNotBlank() && list[it].audioPath == videoPath)
        }.sortedDescending()

        for (idx in removeIndices) {
            list.removeAt(idx)
        }

        currentPlayingIndex = list.indexOfFirst { it.status == SongStatus.PLAYING }
        _queue.value = list

        if (isCurrentlyPlaying) {
            playNext()
        }
    }

    private fun updateItemInQueue(itemId: String, transform: (SongItem) -> SongItem) {
        val list = _queue.value.map {
            if (it.id == itemId) transform(it) else it
        }
        _queue.value = list
    }
}
