package com.kapp.karaoke.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.kapp.karaoke.R
import com.kapp.karaoke.model.SongItem
import com.kapp.karaoke.model.YouTubeVideo
import com.kapp.karaoke.player.QueueManager
import com.kapp.karaoke.storage.StorageManager
import com.kapp.karaoke.ui.theme.*
import com.kapp.karaoke.youtube.YouTubeExtractor
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    queueManager: QueueManager,
    storageManager: StorageManager,
    youtubeExtractor: YouTubeExtractor
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var queryText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var localMatches by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var youtubeResults by remember { mutableStateOf<List<YouTubeVideo>>(emptyList()) }

    fun performSearch() {
        val q = queryText.trim()
        if (q.isBlank()) return

        isSearching = true
        // 1. Search local library first
        val qLower = q.lowercase()
        localMatches = storageManager.scanLocalSongs().filter {
            it.title.lowercase().contains(qLower)
        }

        // 2. Search YouTube
        coroutineScope.launch {
            try {
                youtubeResults = youtubeExtractor.searchVideos(q, 25)
            } finally {
                isSearching = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KaraokeBg)
            .padding(12.dp)
    ) {
        // Search Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text(stringResource(R.string.search_placeholder), color = KaraokeTextDim, fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KaraokePrimary,
                    unfocusedBorderColor = KaraokeBorder,
                    focusedTextColor = KaraokeText,
                    unfocusedTextColor = KaraokeText
                ),
                trailingIcon = {
                    if (queryText.isNotEmpty()) {
                        IconButton(onClick = { queryText = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = KaraokeTextDim)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { performSearch() },
                colors = ButtonDefaults.buttonColors(containerColor = KaraokePrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.tvFocusBorder(RoundedCornerShape(10.dp))
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = KaraokeText)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isSearching) {
            LinearProgressIndicator(
                color = KaraokePrimary,
                trackColor = KaraokeBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Search Results: Local First, then YouTube
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Local Library Matches Section
            if (localMatches.isNotEmpty()) {
                item {
                    Text(
                        text = "${stringResource(R.string.header_local_results)} (${localMatches.size})",
                        color = KaraokeSuccess,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(localMatches) { song ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = KaraokeSuccess, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    color = KaraokeText,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (song.durationFormatted.isNotBlank()) "Local File  •  ${song.durationFormatted}" else "Local File",
                                        color = KaraokeTextDim,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!song.videoQuality.isNullOrBlank()) {
                                        Surface(
                                            color = KaraokeCardElevated,
                                            shape = RoundedCornerShape(4.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, KaraokePrimary.copy(alpha = 0.6f))
                                        ) {
                                            Text(
                                                text = song.videoQuality,
                                                color = KaraokePrimary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    queueManager.addLocalSong(song)
                                    Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = KaraokeSuccess),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.tvFocusBorder(RoundedCornerShape(8.dp))
                            ) {
                                Text(stringResource(R.string.btn_add_to_queue), fontSize = 12.sp, color = KaraokeText)
                            }
                        }
                    }
                }
            }

            // YouTube Results Section
            if (youtubeResults.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.header_youtube_results)} (${youtubeResults.size})",
                        color = KaraokePrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(youtubeResults) { video ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicVideo, contentDescription = null, tint = KaraokePrimary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = video.title,
                                    color = KaraokeText,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${video.uploader}  •  ${video.durationText}",
                                    color = KaraokeTextDim,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = {
                                    queueManager.addYouTubeSong(video.title, video.url)
                                    Toast.makeText(context, "Added to queue. Downloading...", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = KaraokePrimary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.tvFocusBorder(RoundedCornerShape(8.dp))
                            ) {
                                Text(stringResource(R.string.btn_add_to_queue), fontSize = 12.sp, color = KaraokeText)
                            }
                        }
                    }
                }
            }
        }
    }
}
