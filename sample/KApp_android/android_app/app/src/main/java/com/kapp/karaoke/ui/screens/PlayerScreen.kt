package com.kapp.karaoke.ui.screens

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.kapp.karaoke.R
import com.kapp.karaoke.player.KaraokePlayerManager
import com.kapp.karaoke.player.QueueManager
import com.kapp.karaoke.server.KaraokeHttpServer
import com.kapp.karaoke.ui.QrCodeGenerator
import com.kapp.karaoke.ui.theme.*

@Composable
fun PlayerScreen(
    playerManager: KaraokePlayerManager,
    queueManager: QueueManager,
    areControlsVisible: Boolean = true,
    onToggleControls: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val playerState by playerManager.playerState.collectAsState()
    val currentSong by playerManager.currentSong.collectAsState()

    var showQrOverlay by remember { mutableStateOf(false) }
    val localIp = remember { KaraokeHttpServer.getLocalIpAddress(context) }
    val remoteUrl = "http://$localIp:8080"
    val qrBitmap = remember(remoteUrl) { QrCodeGenerator.generateQrBitmap(remoteUrl, 320) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KaraokeBg)
            .padding(if (areControlsVisible) 12.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Bar when controls are visible (Easily accessible by TV remote D-pad!)
        if (areControlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hide Controls Button
                Button(
                    onClick = { onToggleControls(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = KaraokeCardElevated),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.hide_controls),
                        tint = KaraokePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.hide_controls),
                        fontSize = 12.sp,
                        color = KaraokeText
                    )
                }

                // Current Song Title
                Text(
                    text = if (playerState.currentTitle.isNotBlank()) playerState.currentTitle else "No song currently playing",
                    color = KaraokePrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )

                // Remote QR Button
                Button(
                    onClick = { showQrOverlay = !showQrOverlay },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showQrOverlay) KaraokePrimary else KaraokeCardElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = "QR Code",
                        tint = if (showQrOverlay) KaraokeBg else KaraokePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_remote_qr),
                        fontSize = 12.sp,
                        color = if (showQrOverlay) KaraokeBg else KaraokeText,
                        fontWeight = if (showQrOverlay) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Video Player Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(if (areControlsVisible) 12.dp else 0.dp))
                .background(KaraokeCard)
                .pointerInput(Unit) {
                    detectTapGestures {
                        onToggleControls(!areControlsVisible)
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = playerManager.exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // When controls are hidden, keep both "Show Controls" and "Remote QR" buttons accessible
            if (!areControlsVisible) {
                // Show Controls Button (Top-Left)
                Button(
                    onClick = { onToggleControls(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = KaraokeCardElevated.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .tvFocusBorder(RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.show_controls),
                        tint = KaraokePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.show_controls),
                        fontSize = 12.sp,
                        color = KaraokeText
                    )
                }

                // Remote QR Button (Top-Right)
                Button(
                    onClick = { showQrOverlay = !showQrOverlay },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showQrOverlay) KaraokePrimary else KaraokeCardElevated.copy(alpha = 0.85f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .tvFocusBorder(RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = "QR Code",
                        tint = if (showQrOverlay) KaraokeBg else KaraokePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_remote_qr),
                        fontSize = 12.sp,
                        color = if (showQrOverlay) KaraokeBg else KaraokeText,
                        fontWeight = if (showQrOverlay) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // QR Code appears at right-hand top as a sleek, non-blocking overlay card
            if (showQrOverlay && qrBitmap != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = if (areControlsVisible) 10.dp else 52.dp,
                            end = 12.dp
                        )
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

        if (areControlsVisible) {
            Spacer(modifier = Modifier.height(10.dp))

        // Progress Seek Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(playerState.positionFormatted, fontSize = 12.sp, color = KaraokeTextDim)
            Slider(
                value = playerState.positionMs.toFloat(),
                onValueChange = { newPos -> playerManager.seekTo(newPos.toLong()) },
                valueRange = 0f..(playerState.durationMs.coerceAtLeast(1L).toFloat()),
                colors = SliderDefaults.colors(
                    thumbColor = KaraokePrimary,
                    activeTrackColor = KaraokePrimary,
                    inactiveTrackColor = KaraokeBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(playerState.durationFormatted, fontSize = 12.sp, color = KaraokeTextDim)
        }

        // Playback Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Pause
            IconButton(
                onClick = { playerManager.togglePlayPause() },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(KaraokePrimary)
                    .tvFocusBorder(CircleShape, borderWidth = 3.dp)
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = KaraokeText,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Stop
            IconButton(
                onClick = { queueManager.stop() },
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(KaraokeSecondary)
                    .tvFocusBorder(CircleShape, borderWidth = 3.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = KaraokeText)
            }

            // Next / Skip
            IconButton(
                onClick = { queueManager.playNext() },
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(KaraokeCardElevated)
                    .tvFocusBorder(CircleShape, borderWidth = 3.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = KaraokeText)
            }

            // Replay
            IconButton(
                onClick = { playerManager.seekTo(0L) },
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(KaraokeCardElevated)
                    .tvFocusBorder(CircleShape, borderWidth = 3.dp)
            ) {
                Icon(Icons.Default.Replay, contentDescription = "Replay", tint = KaraokeText)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Vocal Toggle Button & Volume Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vocal Toggle (Karaoke Mode)
            Button(
                onClick = { playerManager.toggleVocals() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (playerState.isVocalsOn) KaraokePrimary else KaraokeSecondary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .tvFocusBorder(RoundedCornerShape(10.dp), borderWidth = 3.dp)
            ) {
                Text(
                    text = if (playerState.isVocalsOn) stringResource(R.string.vocals_on) else stringResource(R.string.vocals_off),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Volume Slider
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = "Volume", tint = KaraokeTextDim, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Slider(
                    value = playerState.volume.toFloat(),
                    onValueChange = { playerManager.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = KaraokeSuccess,
                        activeTrackColor = KaraokeSuccess,
                        inactiveTrackColor = KaraokeBorder
                    )
                )
            }
        }
        }
    }
}
