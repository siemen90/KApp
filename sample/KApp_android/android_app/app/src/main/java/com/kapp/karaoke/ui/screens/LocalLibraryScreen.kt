package com.kapp.karaoke.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.karaoke.R
import com.kapp.karaoke.model.SongItem
import com.kapp.karaoke.player.QueueManager
import com.kapp.karaoke.storage.StorageManager
import com.kapp.karaoke.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun LocalLibraryScreen(
    queueManager: QueueManager,
    storageManager: StorageManager,
    onNavigateToPlayer: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val localSongs by storageManager.localSongs.collectAsState()
    var filterText by remember { mutableStateOf("") }
    var songToDelete by remember { mutableStateOf<SongItem?>(null) }
    var storageSpaceText by remember { mutableStateOf("") }

    fun loadSongs() {
        coroutineScope.launch(Dispatchers.IO) {
            storageManager.refreshLocalSongs()
            storageSpaceText = storageManager.getFormattedStorageSpace()
        }
    }

    LaunchedEffect(Unit) {
        loadSongs()
    }

    val filteredSongs = remember(localSongs, filterText) {
        if (filterText.isBlank()) localSongs
        else localSongs.filter { it.title.lowercase().contains(filterText.lowercase()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KaraokeBg)
            .padding(12.dp)
    ) {
        // Header with Refresh & Storage Free Space
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tab_library),
                color = KaraokeText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (storageSpaceText.isNotBlank()) {
                    Surface(
                        color = KaraokeCard,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, KaraokeBorder)
                    ) {
                        Text(
                            text = stringResource(R.string.storage_free, storageSpaceText),
                            color = KaraokeTextDim,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { loadSongs() },
                    modifier = Modifier.tvFocusBorder(CircleShape)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = KaraokePrimary)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Filter text box
        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text(stringResource(R.string.search_local_placeholder), color = KaraokeTextDim, fontSize = 14.sp) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KaraokePrimary,
                unfocusedBorderColor = KaraokeBorder,
                focusedTextColor = KaraokeText,
                unfocusedTextColor = KaraokeText
            ),
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusBorder(RoundedCornerShape(10.dp))
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.empty_library),
                    color = KaraokeTextDim,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSongs) { song ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Song Info Area (D-pad focusable & clickable with OK)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        queueManager.addLocalSong(song)
                                        Toast.makeText(context, "${context.getString(R.string.btn_add_to_queue)}: ${song.title}", Toast.LENGTH_SHORT).show()
                                    }
                                    .tvFocusBorder(RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AudioFile,
                                    contentDescription = null,
                                    tint = KaraokePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        color = KaraokeText,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (song.durationFormatted.isNotBlank()) "Local File  •  ${song.durationFormatted}" else "Local File",
                                            color = KaraokeTextDim,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (!song.videoQuality.isNullOrBlank()) {
                                            Surface(
                                                color = KaraokeCardElevated,
                                                shape = RoundedCornerShape(4.dp),
                                                border = BorderStroke(1.dp, KaraokePrimary.copy(alpha = 0.5f))
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
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Add to Queue button (D-pad accessible: RIGHT from song info)
                            IconButton(
                                onClick = {
                                    queueManager.addLocalSong(song)
                                    Toast.makeText(context, "${context.getString(R.string.btn_add_to_queue)}: ${song.title}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .tvFocusBorder(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = stringResource(R.string.btn_add_to_queue),
                                    tint = KaraokeSuccess
                                )
                            }

                            // Delete button (D-pad accessible)
                            IconButton(
                                onClick = { songToDelete = song },
                                modifier = Modifier
                                    .size(38.dp)
                                    .tvFocusBorder(CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.btn_delete),
                                    tint = KaraokeSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        if (songToDelete != null) {
            val song = songToDelete!!
            AlertDialog(
                onDismissRequest = { songToDelete = null },
                title = { Text(stringResource(R.string.delete_confirm_title), color = KaraokeText) },
                text = {
                    Text(
                        "${stringResource(R.string.delete_confirm_msg)}\n\n${song.title}",
                        color = KaraokeTextDim
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            queueManager.removeSongByFolderOrPath(song.folderPath, song.videoPath)
                            storageManager.deleteSongFolder(song.folderPath)
                            songToDelete = null
                            loadSongs()
                        },
                        modifier = Modifier.tvFocusBorder(RoundedCornerShape(8.dp))
                    ) {
                        Text(stringResource(R.string.confirm), color = KaraokeSecondary)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { songToDelete = null },
                        modifier = Modifier.tvFocusBorder(RoundedCornerShape(8.dp))
                    ) {
                        Text(stringResource(R.string.cancel), color = KaraokeText)
                    }
                },
                containerColor = KaraokeCard
            )
        }
    }
}
