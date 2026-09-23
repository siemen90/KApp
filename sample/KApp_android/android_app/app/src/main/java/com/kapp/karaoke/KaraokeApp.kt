package com.kapp.karaoke

import android.app.Application
import com.kapp.karaoke.download.SongDownloadManager
import com.kapp.karaoke.player.KaraokePlayerManager
import com.kapp.karaoke.player.QueueManager
import com.kapp.karaoke.server.KaraokeHttpServer
import com.kapp.karaoke.storage.StorageManager
import com.kapp.karaoke.youtube.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KaraokeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var storageManager: StorageManager
        private set

    lateinit var youtubeExtractor: YouTubeExtractor
        private set

    lateinit var downloadManager: SongDownloadManager
        private set

    lateinit var playerManager: KaraokePlayerManager
        private set

    lateinit var queueManager: QueueManager
        private set

    var httpServer: KaraokeHttpServer? = null
        private set

    override fun onCreate() {
        super.onCreate()

        storageManager = StorageManager(this)
        youtubeExtractor = YouTubeExtractor()
        downloadManager = SongDownloadManager(storageManager, youtubeExtractor)

        playerManager = KaraokePlayerManager(this) {
            queueManager.playNext()
        }

        queueManager = QueueManager(playerManager, downloadManager, appScope)

        try {
            httpServer = KaraokeHttpServer(
                context = this,
                playerManager = playerManager,
                queueManager = queueManager,
                storageManager = storageManager,
                youtubeExtractor = youtubeExtractor,
                scope = appScope,
                port = 8080
            ).apply {
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        httpServer?.stop()
        playerManager.release()
    }
}
