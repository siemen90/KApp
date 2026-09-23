package com.kapp.karaoke.server

import android.content.Context
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kapp.karaoke.model.SongStatus
import com.kapp.karaoke.player.KaraokePlayerManager
import com.kapp.karaoke.player.QueueManager
import com.kapp.karaoke.storage.StorageManager
import com.kapp.karaoke.youtube.YouTubeExtractor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface

class KaraokeHttpServer(
    private val context: Context,
    private val playerManager: KaraokePlayerManager,
    private val queueManager: QueueManager,
    private val storageManager: StorageManager,
    private val youtubeExtractor: YouTubeExtractor,
    private val scope: CoroutineScope,
    port: Int = 8080
) : NanoHTTPD("0.0.0.0", port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            res.addHeader("Access-Control-Allow-Headers", "Content-Type")
            return res
        }

        return try {
            when {
                // Remote web controller page
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html")

                // Status & Queue API
                (uri == "/api/state" || uri == "/api/status") && method == Method.GET -> handleGetFullState()
                uri == "/api/queue" && method == Method.GET -> handleGetQueue()
                uri == "/api/player" && method == Method.GET -> handleGetPlayerState()

                // Playback Control API
                (uri == "/api/player/toggle" || uri == "/api/player/toggle_play") && method == Method.POST -> {
                    playerManager.togglePlayPause()
                    okJson()
                }
                uri == "/api/player/stop" && method == Method.POST -> {
                    queueManager.stop()
                    okJson()
                }
                uri == "/api/player/next" && method == Method.POST -> {
                    queueManager.playNext()
                    okJson()
                }
                (uri == "/api/player/vocal" || uri == "/api/player/toggle_vocals") && method == Method.POST -> {
                    playerManager.toggleVocals()
                    okJson()
                }
                uri == "/api/player/seek" && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val pos = (body["position"] as? Number)?.toLong() ?: 0L
                    playerManager.seekTo(pos)
                    okJson()
                }
                uri == "/api/player/volume" && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val vol = (body["volume"] as? Number)?.toInt() ?: 50
                    playerManager.setVolume(vol)
                    okJson()
                }

                // Queue Action API
                (uri == "/api/queue/play" || uri.startsWith("/api/player/play/") || uri.startsWith("/api/queue/play/")) && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val row = extractRow(uri, body)
                    queueManager.playSongAtIndex(row)
                    okJson()
                }
                (uri == "/api/queue/move_to_next" || uri == "/api/queue/move_next" || uri.startsWith("/api/queue/move_next/")) && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val row = extractRow(uri, body)
                    queueManager.moveToNext(row)
                    okJson()
                }
                (uri == "/api/queue/delete" || uri.startsWith("/api/queue/delete/")) && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val queryId = extractQueryParam(session, "id")
                    val songId = queryId.ifBlank { (body["id"])?.toString() ?: "" }
                    val row = extractRow(uri, body)
                    var deleted = false
                    if (songId.isNotBlank()) {
                        deleted = queueManager.deleteSongById(songId)
                    }
                    if (!deleted) {
                        queueManager.deleteSong(row)
                    }
                    okJson()
                }
                (uri == "/api/queue/replay" || uri.startsWith("/api/queue/replay/") || uri == "/api/queue/unplay" || uri.startsWith("/api/queue/unplay/")) && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val row = extractRow(uri, body)
                    val target = queueManager.queue.value.getOrNull(row)
                    if (target != null && target.status == SongStatus.PLAYED) {
                        queueManager.markAsUnplayed(row)
                    } else {
                        queueManager.playSongAtIndex(row)
                    }
                    okJson()
                }
                (uri == "/api/queue/clear_played" || uri == "/api/queue/clear-played") && method == Method.POST -> {
                    queueManager.clearPlayedSongs()
                    okJson()
                }

                // Adding Songs API
                uri == "/api/queue/add_youtube" && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val url = (body["url"] ?: session.parms["url"] ?: "").toString().trim()
                    var title = extractTitle(session, body)
                    val quality = (body["quality"] ?: session.parms["quality"] ?: "").toString().trim().ifBlank { null }

                    val isCorrupted = title.isBlank() || title == "YouTube Song" || title.contains('\uFFFD') ||
                            (title.length > 2 && title.all { it == '?' || it.isWhitespace() })

                    if (isCorrupted && url.isNotBlank()) {
                        try {
                            val stream = runBlocking { youtubeExtractor.extractStreamUrls(url) }
                            if (!stream?.title.isNullOrBlank() && !stream!!.title.contains('\uFFFD')) {
                                title = stream.title
                            }
                        } catch (e: Exception) { }
                    }

                    if (title.isBlank() || title.contains('\uFFFD')) {
                        title = "YouTube Song"
                    }

                    if (url.isNotBlank()) {
                        queueManager.addYouTubeSong(title, url, preferredQuality = quality)
                    }
                    okJson()
                }
                (uri == "/api/settings/quality" || uri == "/api/settings/video_quality") && method == Method.GET -> {
                    jsonResponse(mapOf(
                        "quality" to com.kapp.karaoke.util.SettingsManager.getVideoQuality(context),
                        "options" to com.kapp.karaoke.util.SettingsManager.QUALITY_OPTIONS
                    ))
                }
                (uri == "/api/settings/quality" || uri == "/api/settings/video_quality") && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val q = (body["quality"] ?: session.parms["quality"] ?: "").toString().trim()
                    if (q.isNotBlank()) {
                        com.kapp.karaoke.util.SettingsManager.setVideoQuality(context, q)
                    }
                    jsonResponse(mapOf(
                        "ok" to true,
                        "quality" to com.kapp.karaoke.util.SettingsManager.getVideoQuality(context)
                    ))
                }
                (uri == "/api/youtube/info") && (method == Method.POST || method == Method.GET) -> {
                    val body = readBodyJson(session)
                    val url = (body["url"] ?: session.parms["url"] ?: "").toString().trim()
                    val title = try {
                        val streams = runBlocking { youtubeExtractor.extractStreamUrls(url) }
                        streams?.title?.ifBlank { "YouTube Song" } ?: "YouTube Song"
                    } catch (e: Exception) {
                        "YouTube Song"
                    }
                    jsonResponse(mapOf("url" to url, "title" to title))
                }
                uri == "/api/queue/add_local" && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val localSongs = storageManager.scanLocalSongs()
                    val title = (body["title"] ?: session.parms["title"] ?: "").toString().trim()
                    val path = (body["path"] ?: session.parms["path"] ?: "").toString().trim()
                    val found = localSongs.firstOrNull {
                        (path.isNotBlank() && it.folderPath == path) ||
                        (title.isNotBlank() && it.title.equals(title, ignoreCase = true))
                    }
                    if (found != null) {
                        queueManager.addLocalSong(found)
                    }
                    okJson()
                }

                // Library API
                uri == "/api/library" && method == Method.GET -> {
                    val songs = storageManager.scanLocalSongs().map {
                        mapOf(
                            "title" to it.title,
                            "duration" to it.durationSeconds,
                            "path" to it.folderPath,
                            "quality" to (it.videoQuality ?: "")
                        )
                    }
                    jsonResponse(songs)
                }
                uri == "/api/library/delete" && method == Method.POST -> {
                    val body = readBodyJson(session)
                    val queryPath = extractQueryParam(session, "path").ifBlank { extractQueryParam(session, "folderPath") }
                    val queryTitle = extractQueryParam(session, "title")
                    val rawPath = queryPath.ifBlank { (body["path"] ?: body["folderPath"])?.toString() ?: "" }
                    val rawTitle = queryTitle.ifBlank { extractTitle(session, body) }

                    android.util.Log.d("KaraokeHttpServer", "Library delete request: path='$rawPath', title='$rawTitle'")

                    var targetFolder = ""
                    var targetVideo = ""

                    if (rawPath.isNotBlank() && java.io.File(rawPath).exists()) {
                        val f = java.io.File(rawPath)
                        targetFolder = if (f.isDirectory) f.absolutePath else f.parentFile?.absolutePath ?: ""
                    }

                    if (targetFolder.isBlank()) {
                        val found = storageManager.scanLocalSongs().firstOrNull {
                            (rawPath.isNotBlank() && it.folderPath == rawPath) ||
                            (rawTitle.isNotBlank() && it.title.equals(rawTitle, ignoreCase = true))
                        }
                        if (found != null) {
                            targetFolder = found.folderPath
                            targetVideo = found.videoPath
                        }
                    }

                    if (targetFolder.isNotBlank()) {
                        queueManager.removeSongByFolderOrPath(targetFolder, targetVideo)
                        val deleted = storageManager.deleteSongFolder(targetFolder)
                        storageManager.refreshLocalSongs()

                        if (deleted) {
                            okJson()
                        } else {
                            jsonResponse(mapOf("ok" to false, "error" to "Failed to delete files on disk"))
                        }
                    } else {
                        jsonResponse(mapOf("ok" to false, "error" to "Song not found"))
                    }
                }

                // YouTube Search API (returns local matches first, then YouTube results!)
                (uri == "/api/youtube/search" || uri.startsWith("/api/youtube/search")) && (method == Method.POST || method == Method.GET) -> {
                    val body = readBodyJson(session)
                    val query = extractQuery(session, body)
                    android.util.Log.d("KaraokeHttpServer", "YouTube search requested for query: '$query'")
                    val results = mutableListOf<Map<String, Any>>()

                    if (query.isNotBlank()) {
                        // 1. Check local library matches first
                        val qLower = query.lowercase()
                        val localList = storageManager.scanLocalSongs()
                        for (song in localList) {
                            if (song.title.lowercase().contains(qLower)) {
                                results.add(
                                    mapOf(
                                        "title" to song.title,
                                        "url" to (song.videoPath.ifBlank { song.folderPath }),
                                        "duration" to song.durationSeconds,
                                        "is_local" to true,
                                        "source" to "local",
                                        "path" to song.folderPath
                                    )
                                )
                            }
                        }

                        // 2. Fetch YouTube results
                        try {
                            val ytVideos = runBlocking { youtubeExtractor.searchVideos(query, 30) }
                            android.util.Log.d("KaraokeHttpServer", "YouTube returned ${ytVideos.size} items for '$query'")
                            for (v in ytVideos) {
                                results.add(
                                    mapOf(
                                        "title" to v.title,
                                        "url" to v.url,
                                        "duration" to v.durationSeconds,
                                        "thumbnail" to v.thumbnailUrl,
                                        "is_local" to false,
                                        "source" to "youtube"
                                    )
                                )
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("KaraokeHttpServer", "Error searching YouTube: ${e.message}", e)
                        }
                    }

                    android.util.Log.d("KaraokeHttpServer", "Total search results returning: ${results.size}")
                    jsonResponse(results)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.localizedMessage ?: "Error")
        }
    }

    private fun handleGetFullState(): Response {
        val state = playerManager.playerState.value
        val queueItems = queueManager.queue.value
        val playingIndex = queueManager.currentPlayingIndex.takeIf { it in queueItems.indices }
            ?: queueItems.indexOfFirst { it.status == SongStatus.PLAYING }

        val playerMap = mapOf(
            "playing" to state.isPlaying,
            "paused" to (!state.isPlaying && state.currentTitle.isNotBlank()),
            "position" to state.positionMs,
            "duration" to state.durationMs,
            "volume" to state.volume,
            "vocals_on" to state.isVocalsOn,
            "current_title" to state.currentTitle,
            "current_row" to playingIndex
        )
        val queueList = queueItems.mapIndexed { idx, item ->
            val isCurrent = (idx == playingIndex && playingIndex != -1 && item.status == SongStatus.PLAYING)
            val isPlayed = item.status == SongStatus.PLAYED || (item.status == SongStatus.PLAYING && !isCurrent)
            val statusStr = when (item.status) {
                SongStatus.WAITING -> "Waiting"
                SongStatus.DOWNLOADING -> "Downloading"
                SongStatus.READY -> "Ready"
                SongStatus.PLAYING -> if (isCurrent) "Playing" else "Played"
                SongStatus.PLAYED -> "Played"
                SongStatus.ERROR -> "Error"
            }
            mapOf(
                "id" to item.id,
                "title" to item.title,
                "status" to statusStr,
                "progress" to item.downloadProgress,
                "video" to item.videoPath,
                "audio" to item.audioPath,
                "inst" to item.instrumentalPath,
                "is_local" to item.isLocal,
                "path" to item.folderPath,
                "folderPath" to item.folderPath,
                "played" to isPlayed,
                "is_current" to isCurrent,
                "row" to idx
            )
        }
        return jsonResponse(mapOf(
            "player" to playerMap,
            "queue" to queueList,
            "video_quality" to com.kapp.karaoke.util.SettingsManager.getVideoQuality(context),
            "quality_options" to com.kapp.karaoke.util.SettingsManager.QUALITY_OPTIONS
        ))
    }

    private fun handleGetQueue(): Response {
        val queueItems = queueManager.queue.value
        val playingIndex = queueManager.currentPlayingIndex.takeIf { it in queueItems.indices }
            ?: queueItems.indexOfFirst { it.status == SongStatus.PLAYING }

        val q = queueItems.mapIndexed { idx, item ->
            val isCurrent = (idx == playingIndex && playingIndex != -1 && item.status == SongStatus.PLAYING)
            val isPlayed = item.status == SongStatus.PLAYED || (item.status == SongStatus.PLAYING && !isCurrent)
            val statusStr = when (item.status) {
                SongStatus.WAITING -> "Waiting"
                SongStatus.DOWNLOADING -> "Downloading"
                SongStatus.READY -> "Ready"
                SongStatus.PLAYING -> if (isCurrent) "Playing" else "Played"
                SongStatus.PLAYED -> "Played"
                SongStatus.ERROR -> "Error"
            }
            mapOf(
                "id" to item.id,
                "title" to item.title,
                "status" to statusStr,
                "progress" to item.downloadProgress,
                "video" to item.videoPath,
                "audio" to item.audioPath,
                "inst" to item.instrumentalPath,
                "is_local" to item.isLocal,
                "path" to item.folderPath,
                "folderPath" to item.folderPath,
                "played" to isPlayed,
                "is_current" to isCurrent,
                "row" to idx
            )
        }
        return jsonResponse(q)
    }

    private fun handleGetPlayerState(): Response {
        val state = playerManager.playerState.value
        val map = mapOf(
            "playing" to state.isPlaying,
            "paused" to (!state.isPlaying && state.currentTitle.isNotBlank()),
            "position" to state.positionMs,
            "duration" to state.durationMs,
            "volume" to state.volume,
            "vocals_on" to state.isVocalsOn,
            "current_title" to state.currentTitle,
            "current_row" to queueManager.currentPlayingIndex
        )
        return jsonResponse(map)
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return try {
            val stream: InputStream = context.assets.open(assetPath)
            val res = newChunkedResponse(Response.Status.OK, mimeType, stream)
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            res.addHeader("Pragma", "no-cache")
            res.addHeader("Expires", "0")
            return res
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found")
        }
    }

    private fun extractQueryParam(session: IHTTPSession, key: String): String {
        var value = ""
        if (!session.queryParameterString.isNullOrBlank()) {
            try {
                for (part in session.queryParameterString.split("&")) {
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2 && kv[0].equals(key, ignoreCase = true)) {
                        value = java.net.URLDecoder.decode(kv[1], "UTF-8").trim()
                        break
                    }
                }
            } catch (e: Exception) { }
        }
        if (value.isBlank()) {
            value = (session.parms[key] ?: session.parms[key.lowercase()]) ?: ""
        }
        return value
    }

    private fun readBodyJson(session: IHTTPSession): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((k, v) in session.parms) {
            result[k] = v
        }

        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            var postData = files["postData"]
                ?: files["content"]?.let { path ->
                    try {
                        val f = java.io.File(path)
                        if (f.exists()) f.readText(Charsets.UTF_8) else null
                    } catch (e: Exception) {
                        null
                    }
                }

            if (!postData.isNullOrBlank()) {
                // If NanoHTTPD parsed raw bytes with ISO-8859-1, fix to UTF-8
                if (postData.contains('\uFFFD') || postData.any { it.code in 0x80..0xFF }) {
                    try {
                        val fixed = String(postData.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                        if (!fixed.contains('\uFFFD')) {
                            postData = fixed
                        }
                    } catch (e: Exception) { }
                }

                val type = object : TypeToken<Map<String, Any>>() {}.type
                val parsed: Map<String, Any>? = gson.fromJson(postData, type)
                if (parsed != null) {
                    for ((k, v) in parsed) {
                        val strVal = v.toString()
                        val isCorrupted = strVal.contains('\uFFFD') ||
                                (strVal.length > 2 && strVal.all { it == '?' || it.isWhitespace() })
                        val existing = result[k]?.toString() ?: ""
                        if (isCorrupted && existing.isNotBlank() && !existing.contains('\uFFFD')) {
                            // Keep uncorrupted query parameter value
                            continue
                        }
                        result[k] = v
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parse exception, parms may already have data
        }
        return result
    }

    private fun extractTitle(session: IHTTPSession, body: Map<String, Any>): String {
        val queryTitle = extractQueryParam(session, "title")
        val bodyTitle = (body["title"] ?: session.parms["title"])?.toString()?.trim() ?: ""

        val isCorrupted = bodyTitle.contains('\uFFFD') ||
                (bodyTitle.isNotBlank() && bodyTitle.length > 2 && bodyTitle.all { it == '?' || it.isWhitespace() })

        return if (!isCorrupted && bodyTitle.isNotBlank()) {
            bodyTitle
        } else if (queryTitle.isNotBlank() && !queryTitle.contains('\uFFFD')) {
            queryTitle
        } else {
            bodyTitle
        }
    }

    private fun extractRow(uri: String, body: Map<String, Any>): Int {
        val pathPart = uri.substringAfterLast("/")
        val fromPath = pathPart.toIntOrNull()
        if (fromPath != null) return fromPath
        return (body["row"] as? Number)?.toInt() ?: 0
    }

    private fun extractQuery(session: IHTTPSession, body: Map<String, Any>): String {
        val qp = extractQueryParam(session, "q").ifBlank { extractQueryParam(session, "query") }
        if (qp.isNotBlank()) return qp
        val bq = (body["query"] ?: body["q"] ?: session.parms["q"] ?: session.parms["query"])?.toString() ?: ""
        return bq.trim()
    }

    private fun jsonResponse(data: Any): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(data))
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return res
    }

    private fun okJson(): Response {
        return jsonResponse(mapOf("ok" to true))
    }

    companion object {
        fun getLocalIpAddress(context: Context): String {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ip != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff,
                        ip shr 8 and 0xff,
                        ip shr 16 and 0xff,
                        ip shr 24 and 0xff
                    )
                }

                // Fallback to NetworkInterface
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (nIface in interfaces) {
                    val addresses = nIface.inetAddresses
                    for (addr in addresses) {
                        val host = addr.hostAddress
                        if (!addr.isLoopbackAddress && host != null && !host.contains(':')) {
                            return host
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return "127.0.0.1"
        }
    }
}
