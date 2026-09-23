package com.kapp.karaoke.youtube

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kapp.karaoke.model.YouTubeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeExtractor {

    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            list.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class StreamInfo(
        val videoUrl: String?,
        val audioUrl: String?,
        val combinedUrl: String?,
        val title: String,
        val durationSeconds: Long,
        val isVideoMp4: Boolean = true
    )

    private val innertubeApiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val consentCookie = "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAnpoIAEaBgiA_LyaBg"

    /**
     * Searches YouTube for karaoke / songs using InnerTube Search API and HTML fallback
     */
    suspend fun searchVideos(query: String, maxResults: Int = 30): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()

        Log.d("YouTubeExtractor", "Searching YouTube directly for: '$q'")

        // 1. Try InnerTube WEB with exact query
        var results = executeInnerTubeSearch(q, "WEB", maxResults)

        // 2. If empty, try InnerTube MWEB with exact query
        if (results.isEmpty()) {
            results = executeInnerTubeSearch(q, "MWEB", maxResults)
        }

        // 3. If InnerTube returned empty, try HTML scrape fallback with exact query
        if (results.isEmpty()) {
            results = executeHtmlSearch(q, maxResults)
        }

        Log.d("YouTubeExtractor", "Search completed. Found ${results.size} videos for '$q'")
        results
    }

    private fun executeInnerTubeSearch(keyword: String, clientName: String, maxResults: Int): List<YouTubeVideo> {
        return try {
            val payload = JsonObject().apply {
                val contextObj = JsonObject().apply {
                    val clientObj = JsonObject().apply {
                        addProperty("clientName", clientName)
                        addProperty("clientVersion", "2.20260918.00.00")
                        addProperty("hl", "en")
                        addProperty("gl", "US")
                    }
                    add("client", clientObj)
                }
                add("context", contextObj)
                addProperty("query", keyword)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?key=$innertubeApiKey&prettyPrint=false")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-Youtube-Client-Name", if (clientName == "MWEB") "2" else "1")
                .header("X-Youtube-Client-Version", "2.20260918.00.00")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("Cookie", consentCookie)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("YouTubeExtractor", "InnerTube [$clientName] returned HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            parseSearchResults(body, maxResults)
        } catch (e: Exception) {
            Log.w("YouTubeExtractor", "InnerTube [$clientName] failed: ${e.message}")
            emptyList()
        }
    }

    private fun executeHtmlSearch(keyword: String, maxResults: Int): List<YouTubeVideo> {
        return try {
            val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", consentCookie)
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return emptyList()

            val marker1 = "var ytInitialData = "
            val idx1 = html.indexOf(marker1)
            var jsonStr = if (idx1 != -1) {
                val endIdx = html.indexOf(";</script>", idx1 + marker1.length)
                if (endIdx != -1) html.substring(idx1 + marker1.length, endIdx) else null
            } else null

            if (jsonStr == null) {
                val marker2 = "window[\"ytInitialData\"] = "
                val idx2 = html.indexOf(marker2)
                if (idx2 != -1) {
                    val endIdx2 = html.indexOf(";</script>", idx2 + marker2.length)
                    if (endIdx2 != -1) html.substring(idx2 + marker2.length, endIdx2) else null
                }
            }

            if (jsonStr != null) {
                parseSearchResults(jsonStr, maxResults)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w("YouTubeExtractor", "HTML search failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseSearchResults(jsonStr: String, maxResults: Int): List<YouTubeVideo> {
        val results = mutableListOf<YouTubeVideo>()
        val seenIds = mutableSetOf<String>()
        try {
            val root = JsonParser.parseString(jsonStr)
            extractVideosRecursive(root, results, seenIds, maxResults)
        } catch (e: Throwable) {
            Log.w("YouTubeExtractor", "Error parsing search results JSON: ${e.message}")
        }
        return results
    }

    private fun extractVideosRecursive(
        element: JsonElement?,
        results: MutableList<YouTubeVideo>,
        seenIds: MutableSet<String>,
        maxResults: Int
    ) {
        if (element == null || results.size >= maxResults) return

        if (element.isJsonObject) {
            val obj = element.asJsonObject

            val vr = obj.getAsJsonObject("videoRenderer")
                ?: obj.getAsJsonObject("compactVideoRenderer")
                ?: obj.getAsJsonObject("videoWithContextRenderer")

            if (vr != null) {
                val videoId = vr.get("videoId")?.asString
                if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                    // Title: can be title.runs, title.simpleText, or headline.runs
                    var title = "Unknown Title"
                    val titleObj = vr.getAsJsonObject("title") ?: vr.getAsJsonObject("headline")
                    if (titleObj != null) {
                        val runs = titleObj.getAsJsonArray("runs")
                        if (runs != null && runs.size() > 0) {
                            val sb = StringBuilder()
                            for (r in runs) {
                                r.asJsonObject?.get("text")?.asString?.let { sb.append(it) }
                            }
                            if (sb.isNotEmpty()) title = sb.toString()
                        } else if (titleObj.has("simpleText")) {
                            title = titleObj.get("simpleText")?.asString ?: title
                        }
                    }

                    // Duration: can be lengthText.simpleText or lengthText.runs
                    var lengthText = ""
                    val lengthObj = vr.getAsJsonObject("lengthText")
                    if (lengthObj != null) {
                        if (lengthObj.has("simpleText")) {
                            lengthText = lengthObj.get("simpleText")?.asString ?: ""
                        } else {
                            val runs = lengthObj.getAsJsonArray("runs")
                            if (runs != null && runs.size() > 0) {
                                val sb = StringBuilder()
                                for (r in runs) {
                                    r.asJsonObject?.get("text")?.asString?.let { sb.append(it) }
                                }
                                lengthText = sb.toString()
                            }
                        }
                    }
                    val durationSeconds = parseDuration(lengthText)

                    // Uploader: can be ownerText or shortBylineText
                    var uploader = ""
                    val ownerObj = vr.getAsJsonObject("ownerText") ?: vr.getAsJsonObject("shortBylineText")
                    if (ownerObj != null) {
                        val runs = ownerObj.getAsJsonArray("runs")
                        if (runs != null && runs.size() > 0) {
                            uploader = runs.get(0).asJsonObject?.get("text")?.asString ?: ""
                        } else if (ownerObj.has("simpleText")) {
                            uploader = ownerObj.get("simpleText")?.asString ?: ""
                        }
                    }

                    // Thumbnail
                    var thumbnailUrl = ""
                    val thumbObj = vr.getAsJsonObject("thumbnail")
                    val thumbs = thumbObj?.getAsJsonArray("thumbnails")
                    if (thumbs != null && thumbs.size() > 0) {
                        thumbnailUrl = thumbs.get(thumbs.size() - 1).asJsonObject?.get("url")?.asString ?: ""
                    }

                    results.add(
                        YouTubeVideo(
                            id = videoId,
                            title = title.trim(),
                            url = "https://www.youtube.com/watch?v=$videoId",
                            durationSeconds = durationSeconds,
                            durationText = lengthText,
                            uploader = uploader,
                            thumbnailUrl = thumbnailUrl
                        )
                    )
                }
                return
            }

            // Recurse into object properties
            for ((_, child) in obj.entrySet()) {
                if (results.size >= maxResults) break
                extractVideosRecursive(child, results, seenIds, maxResults)
            }
        } else if (element.isJsonArray) {
            val arr = element.asJsonArray
            for (child in arr) {
                if (results.size >= maxResults) break
                extractVideosRecursive(child, results, seenIds, maxResults)
            }
        }
    }

    /**
     * Extracts direct playable/downloadable stream URLs.
     * 1. Fetches the YouTube watch page to populate session cookies and visitor ID token.
     * 2. Calls InnerTube player endpoint with VISIONOS client, retrieving direct HTTPS streams.
     */
    suspend fun extractStreamUrls(videoIdOrUrl: String, targetHeight: Int = 720): StreamInfo? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(videoIdOrUrl) ?: return@withContext null

        try {
            // Step 1: Visit watch page to establish session cookies and extract visitorData
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val watchReq = Request.Builder()
                .url(watchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val watchResp = client.newCall(watchReq).execute()
            val watchHtml = watchResp.body?.string() ?: ""

            var visitorId = ""
            val visMatch = Pattern.compile("\"(?:VISITOR_DATA|visitorData)\":\"([^\"]+)\"").matcher(watchHtml)
            if (visMatch.find()) {
                visitorId = visMatch.group(1) ?: ""
            }

            var signatureTimestamp = 20711
            val stsMatch = Pattern.compile("\"signatureTimestamp\":(\\d+)").matcher(watchHtml)
            if (stsMatch.find()) {
                signatureTimestamp = stsMatch.group(1)?.toIntOrNull() ?: 20711
            }

            // Step 2: Query InnerTube player with VISIONOS client
            val payload = JsonObject().apply {
                addProperty("videoId", videoId)
                add("context", JsonObject().apply {
                    add("client", JsonObject().apply {
                        addProperty("clientName", "VISIONOS")
                        addProperty("clientVersion", "1.02")
                        addProperty("platform", "TABLET")
                        addProperty("hl", "en")
                        addProperty("gl", "US")
                        addProperty("utcOffsetMinutes", 0)
                    })
                })
                add("playbackContext", JsonObject().apply {
                    add("contentPlaybackContext", JsonObject().apply {
                        addProperty("signatureTimestamp", signatureTimestamp)
                    })
                })
            }

            val playerReqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("X-Youtube-Client-Name", "101")
                .header("X-Youtube-Client-Version", "1.02")
                .header("Origin", "https://www.youtube.com")
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")

            if (visitorId.isNotBlank()) {
                playerReqBuilder.header("X-Goog-Visitor-Id", visitorId)
            }

            val playerResp = client.newCall(playerReqBuilder.build()).execute()
            val body = playerResp.body?.string() ?: return@withContext null
            val root = JsonParser.parseString(body).asJsonObject

            val playability = root.getAsJsonObject("playabilityStatus")
            val playStatus = playability?.get("status")?.asString
            if (playStatus != null && playStatus != "OK") {
                Log.w("YouTubeExtractor", "Video $videoId playabilityStatus: $playStatus (reason: ${playability.get("reason")?.asString})")
            }

            val videoDetails = root.getAsJsonObject("videoDetails")
            val title = videoDetails?.get("title")?.asString ?: "Song_$videoId"
            val durationSeconds = videoDetails?.get("lengthSeconds")?.asString?.toLongOrNull() ?: 0L

            val streamingData = root.getAsJsonObject("streamingData") ?: return@withContext null

            var bestCombinedUrl: String? = null
            var bestVideoUrl: String? = null
            var bestAudioUrl: String? = null

            // Check combined formats with direct url
            val formats = streamingData.getAsJsonArray("formats")
            if (formats != null) {
                for (fmt in formats) {
                    val obj = fmt.asJsonObject
                    val url = obj.get("url")?.asString
                    if (!url.isNullOrBlank()) {
                        bestCombinedUrl = url
                        break
                    }
                }
            }

            data class VideoCandidate(val url: String, val height: Int, val bitrate: Int, val isMp4: Boolean)
            val videoCandidates = mutableListOf<VideoCandidate>()
            var chosenVideo: VideoCandidate? = null

            // Check adaptive formats with direct url
            val adaptive = streamingData.getAsJsonArray("adaptiveFormats")
            if (adaptive != null) {
                var maxAacBitrate = 0
                var maxAudioBitrate = 0
                var bestAacUrl: String? = null

                for (fmt in adaptive) {
                    val obj = fmt.asJsonObject
                    val mimeType = obj.get("mimeType")?.asString ?: ""
                    val url = obj.get("url")?.asString ?: continue

                    if (mimeType.startsWith("audio/mp4")) {
                        // Priority 1: Native AAC audio in MP4 container
                        val bitrate = obj.get("bitrate")?.asInt ?: 0
                        if (bitrate > maxAacBitrate) {
                            maxAacBitrate = bitrate
                            bestAacUrl = url
                        }
                    } else if (mimeType.startsWith("audio/")) {
                        // Fallback: WebM/Opus audio
                        val bitrate = obj.get("bitrate")?.asInt ?: 0
                        if (bitrate > maxAudioBitrate) {
                            maxAudioBitrate = bitrate
                            bestAudioUrl = url
                        }
                    } else if (mimeType.startsWith("video/")) {
                        val height = obj.get("height")?.asInt ?: 0
                        val bitrate = obj.get("bitrate")?.asInt ?: 0
                        if (height > 0) {
                            videoCandidates.add(
                                VideoCandidate(
                                    url = url,
                                    height = height,
                                    bitrate = bitrate,
                                    isMp4 = mimeType.startsWith("video/mp4")
                                )
                            )
                        }
                    }
                }

                if (videoCandidates.isNotEmpty()) {
                    // Pick the highest resolution available <= targetHeight
                    val eligible = videoCandidates.filter { it.height <= targetHeight }
                    chosenVideo = if (eligible.isNotEmpty()) {
                        eligible.maxWithOrNull(
                            compareBy<VideoCandidate> { it.height }
                                .thenBy { if (it.isMp4) 1 else 0 }
                                .thenBy { it.bitrate }
                        )
                    } else {
                        // Fallback if none <= targetHeight: pick lowest height > targetHeight (closest available)
                        videoCandidates.minWithOrNull(
                            compareBy<VideoCandidate> { it.height }
                                .thenByDescending { if (it.isMp4) 1 else 0 }
                        )
                    }
                    if (chosenVideo != null) {
                        bestVideoUrl = chosenVideo.url
                    }
                }

                // Prefer native AAC in MP4 container; fallback to other audio
                bestAudioUrl = bestAacUrl ?: bestAudioUrl
            }

            StreamInfo(
                videoUrl = bestVideoUrl ?: bestCombinedUrl,
                audioUrl = bestAudioUrl ?: bestCombinedUrl ?: bestVideoUrl,
                combinedUrl = bestCombinedUrl,
                title = title,
                durationSeconds = durationSeconds,
                isVideoMp4 = chosenVideo?.isMp4 ?: true
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed
        }
        val patterns = listOf(
            Regex("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/)([^#&?]+)"),
            Regex("^[a-zA-Z0-9_-]{11}$")
        )
        for (pattern in patterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val group = if (match.groupValues.size > 1) match.groupValues[1] else match.groupValues[0]
                if (group.length == 11) return group
            }
        }
        return null
    }

    private fun parseDuration(text: String): Long {
        if (text.isBlank()) return 0L
        val parts = text.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }
}
