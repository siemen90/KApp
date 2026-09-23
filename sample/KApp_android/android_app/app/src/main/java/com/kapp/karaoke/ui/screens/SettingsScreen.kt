package com.kapp.karaoke.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.karaoke.R
import com.kapp.karaoke.server.KaraokeHttpServer
import com.kapp.karaoke.storage.StorageManager
import com.kapp.karaoke.ui.QrCodeGenerator
import com.kapp.karaoke.ui.theme.*

@Composable
fun SettingsScreen(storageManager: StorageManager) {
    val context = LocalContext.current
    val downloadDir = remember { storageManager.getKAppDownloadDir().absolutePath }
    val localIp = remember { KaraokeHttpServer.getLocalIpAddress(context) }
    val remoteUrl = "http://$localIp:8080"
    val qrBitmap = remember(remoteUrl) { QrCodeGenerator.generateQrBitmap(remoteUrl, 400) }
    var currentLang by remember { mutableStateOf(com.kapp.karaoke.util.LocaleHelper.getLanguage(context)) }
    var currentQuality by remember { mutableStateOf(com.kapp.karaoke.util.SettingsManager.getVideoQuality(context)) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(KaraokeBg)
            .padding(16.dp)
    ) {
        val isWideScreen = maxWidth >= 600.dp

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Row: Title & Build Time Version Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tab_settings),
                    color = KaraokeText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = KaraokeCardElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, KaraokeBorder)
                ) {
                    Text(
                        text = com.kapp.karaoke.BuildConfig.BUILD_TIME_VERSION,
                        color = KaraokeTextDim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isWideScreen) {
                // TV / Landscape 2-Column Layout (QR Code prominently visible on right, no scroll needed!)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Column: Language, Video Quality & Download Location
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        LanguageCard(
                            currentLang = currentLang,
                            onLanguageSelected = { lang ->
                                if (currentLang != lang) {
                                currentLang = lang
                                com.kapp.karaoke.util.LocaleHelper.setLanguage(context, lang)
                                (context as? android.app.Activity)?.recreate()
                            }
                        }
                    )

                        VideoQualityCard(
                            currentQuality = currentQuality,
                            onQualitySelected = { q ->
                                currentQuality = q
                                com.kapp.karaoke.util.SettingsManager.setVideoQuality(context, q)
                            }
                        )

                        DownloadLocationCard(downloadDir = downloadDir)
                    }

                    // Right Column: Remote Server & Big QR Code
                    Column(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        RemoteServerCard(
                            remoteUrl = remoteUrl,
                            qrBitmap = qrBitmap
                        )
                    }
                }
            } else {
                // Phone / Portrait Single Column Layout (Scrollable with full D-pad focusability)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LanguageCard(
                        currentLang = currentLang,
                        onLanguageSelected = { lang ->
                            if (currentLang != lang) {
                                currentLang = lang
                                com.kapp.karaoke.util.LocaleHelper.setLanguage(context, lang)
                                (context as? android.app.Activity)?.recreate()
                            }
                        }
                    )

                    VideoQualityCard(
                        currentQuality = currentQuality,
                        onQualitySelected = { q ->
                            currentQuality = q
                            com.kapp.karaoke.util.SettingsManager.setVideoQuality(context, q)
                        }
                    )

                    DownloadLocationCard(downloadDir = downloadDir)

                    RemoteServerCard(
                        remoteUrl = remoteUrl,
                        qrBitmap = qrBitmap
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageCard(
    currentLang: String,
    onLanguageSelected: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusBorder(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null, tint = KaraokePrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_language),
                    color = KaraokeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.language_hint),
                color = KaraokeTextDim,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Chinese Button
                Button(
                    onClick = { onLanguageSelected("zh") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentLang == "zh") KaraokePrimary else KaraokeCardElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusBorder(RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = "中文 (Chinese)",
                        fontWeight = if (currentLang == "zh") FontWeight.Bold else FontWeight.Normal,
                        color = KaraokeText,
                        fontSize = 13.sp
                    )
                }

                // English Button
                Button(
                    onClick = { onLanguageSelected("en") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentLang == "en") KaraokePrimary else KaraokeCardElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .tvFocusBorder(RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = "English",
                        fontWeight = if (currentLang == "en") FontWeight.Bold else FontWeight.Normal,
                        color = KaraokeText,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadLocationCard(downloadDir: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .tvFocusBorder(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = KaraokePrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_download_dir),
                    color = KaraokeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = downloadDir,
                color = KaraokeSuccess,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Every YouTube song download creates its own folder inside this directory with the video, audio, and metadata.",
                color = KaraokeTextDim,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RemoteServerCard(
    remoteUrl: String,
    qrBitmap: android.graphics.Bitmap?
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .tvFocusBorder(RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = KaraokeSecondary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.remote_server_title),
                    color = KaraokeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.remote_scan_hint),
                color = KaraokeTextDim,
                fontSize = 13.sp
            )

            Text(
                text = remoteUrl,
                color = KaraokePrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(6.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Guests on the same WiFi can scan this code to search songs, add to queue, and control playback from their phone.",
                color = KaraokeTextDim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun VideoQualityCard(
    currentQuality: String,
    onQualitySelected: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KaraokeCard),
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusBorder(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicVideo, contentDescription = null, tint = KaraokePrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_video_quality),
                    color = KaraokeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_video_quality_hint),
                color = KaraokeTextDim,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.kapp.karaoke.util.SettingsManager.QUALITY_OPTIONS.forEach { quality ->
                    val isSelected = currentQuality.equals(quality, ignoreCase = true)
                    Button(
                        onClick = { onQualitySelected(quality) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) KaraokePrimary else KaraokeCardElevated
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .tvFocusBorder(RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = quality,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = KaraokeText,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
