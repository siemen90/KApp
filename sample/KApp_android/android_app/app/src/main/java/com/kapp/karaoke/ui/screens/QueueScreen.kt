package com.kapp.karaoke.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.karaoke.R
import com.kapp.karaoke.model.SongItem
import com.kapp.karaoke.model.SongStatus
import com.kapp.karaoke.player.QueueManager
import com.kapp.karaoke.server.KaraokeHttpServer
import com.kapp.karaoke.ui.QrCodeGenerator
import com.kapp.karaoke.ui.theme.*

@Composable
fun QueueScreen(
    queueManager: QueueManager,
    onNavigateToPlayer: () -> Unit = {}
) {
    val context = LocalContext.current
    val queue by queueManager.queue.collectAsState()
    var showQrOverlay by remember { mutableStateOf(false) }
    val localIp = remember { KaraokeHttpServer.getLocalIpAddress(context) }
    val remoteUrl = "http://$localIp:8080"
    val qrBitmap = remember(remoteUrl) { QrCodeGenerator.generateQrBitmap(remoteUrl, 320) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KaraokeBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val playedCount = queue.count { it.status == SongStatus.PLAYED }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tab_queue),
                    color = KaraokeText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Remote QR Button right in QueueScreen!
                    Button(
                        onClick = { showQrOverlay = !showQrOverlay },
                        colors = ButtonDefaults.buttonColors(containerColor = KaraokePrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .tvFocusBorder(RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = "QR Code",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.btn_remote_qr),
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }

                    if (playedCount >= 2) {
                        Button(
                            onClick = { queueManager.clearPlayedSongs() },
                            colors = ButtonDefaults.buttonColors(containerColor = KaraokeSecondary),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .tvFocusBorder(RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.btn_clear_played),
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                    Text(
                        text = "${queue.size} songs",
                        color = KaraokeTextDim,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.empty_queue),
                        color = KaraokeTextDim,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(queue) { index, song ->
                        QueueItemCard(
                            index = index,
                            song = song,
                            onPlay = {
                                queueManager.playSongAtIndex(index)
                                onNavigateToPlayer()
                            },
                            onMarkAsUnplayed = { queueManager.markAsUnplayed(index) },
                            onMoveToNext = { queueManager.moveToNext(index) },
                            onMoveUp = { queueManager.moveUp(index) },
                            onMoveDown = { queueManager.moveDown(index) },
                            onDelete = { queueManager.deleteSong(index) }
                        )
                    }
                }
            }
        }

        // Floating QR Overlay at top-right corner
        if (showQrOverlay && qrBitmap != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 12.dp)
                    .width(180.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = KaraokeCardElevated.copy(alpha = 0.95f)),
                border = BorderStroke(1.5.dp, KaraokeBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.btn_remote_qr),
                            color = KaraokePrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { showQrOverlay = false },
                            modifier = Modifier
                                .size(22.dp)
                                .tvFocusBorder(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = KaraokeTextDim,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(4.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = remoteUrl,
                        color = KaraokeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = stringResource(R.string.remote_scan_hint),
                        color = KaraokeTextDim,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun QueueItemCard(
    index: Int,
    song: SongItem,
    onPlay: () -> Unit,
    onMarkAsUnplayed: () -> Unit,
    onMoveToNext: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val isPlaying = song.status == SongStatus.PLAYING
    val isPlayed = song.status == SongStatus.PLAYED

    val cardBg = when {
        isPlaying -> KaraokePrimary.copy(alpha = 0.25f)
        isPlayed -> KaraokePlayedBg
        else -> KaraokeCard
    }

    val borderModifier = when {
        isPlaying -> Modifier.border(1.5.dp, KaraokePrimary, RoundedCornerShape(10.dp))
        isPlayed -> Modifier.border(1.dp, KaraokePlayedBorder, RoundedCornerShape(10.dp))
        else -> Modifier
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Focusable & Clickable Song Info (Press OK on D-pad to Play!)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPlay() }
                        .tvFocusBorder(RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Index badge
                    Text(
                        text = "${index + 1}",
                        color = when {
                            isPlaying -> KaraokePrimary
                            isPlayed -> KaraokePlayedText
                            else -> KaraokeTextDim
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.width(28.dp)
                    )

                    // Title & status
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = if (isPlayed) KaraokePlayedText else KaraokeText,
                            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when (song.status) {
                                SongStatus.WAITING -> KaraokeWarning
                                SongStatus.DOWNLOADING -> KaraokePrimary
                                SongStatus.READY -> KaraokeSuccess
                                SongStatus.PLAYING -> KaraokePrimary
                                SongStatus.PLAYED -> KaraokePlayedText
                                SongStatus.ERROR -> KaraokeSecondary
                            }
                            val statusText = when (song.status) {
                                SongStatus.WAITING -> stringResource(R.string.status_waiting)
                                SongStatus.DOWNLOADING -> {
                                    if (song.downloadProgress >= 50) {
                                        "${stringResource(R.string.status_removing_vocals)} ${song.downloadProgress}%"
                                    } else {
                                        "${stringResource(R.string.status_downloading)} ${song.downloadProgress}%"
                                    }
                                }
                                SongStatus.READY -> stringResource(R.string.status_ready)
                                SongStatus.PLAYING -> stringResource(R.string.status_playing)
                                SongStatus.PLAYED -> stringResource(R.string.status_played)
                                SongStatus.ERROR -> stringResource(R.string.status_error)
                            }
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (song.durationFormatted.isNotBlank()) {
                                Text(
                                    text = "  •  ${song.durationFormatted}",
                                    color = if (isPlayed) KaraokePlayedText.copy(alpha = 0.7f) else KaraokeTextDim,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // D-pad accessible Action Buttons
                // If song was played, provide quick "Convert to Unplayed / Sing Again" button
                if (isPlayed) {
                    IconButton(
                        onClick = onMarkAsUnplayed,
                        modifier = Modifier
                            .size(38.dp)
                            .tvFocusBorder(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = stringResource(R.string.btn_mark_unplayed),
                            tint = KaraokeSuccess
                        )
                    }
                }

                // Play / Action icon
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(38.dp)
                        .tvFocusBorder(CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.PlayCircle,
                        contentDescription = "Play",
                        tint = if (isPlaying) KaraokePrimary else if (isPlayed) KaraokePlayedText else KaraokeText
                    )
                }

                // Move to next icon
                IconButton(
                    onClick = onMoveToNext,
                    modifier = Modifier
                        .size(38.dp)
                        .tvFocusBorder(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.LowPriority,
                        contentDescription = "Move to Next",
                        tint = KaraokeTextDim
                    )
                }

                // Delete icon
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(38.dp)
                        .tvFocusBorder(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = KaraokeSecondary
                    )
                }
            }

            // If downloading, show progress bar
            if (song.status == SongStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (song.downloadProgress / 100f).coerceIn(0f, 1f) },
                    color = KaraokePrimary,
                    trackColor = KaraokeBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
