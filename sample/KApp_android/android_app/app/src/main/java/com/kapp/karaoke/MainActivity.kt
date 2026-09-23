package com.kapp.karaoke

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kapp.karaoke.ui.screens.*
import com.kapp.karaoke.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class NavTab {
    PLAYER,
    QUEUE,
    SEARCH,
    LIBRARY,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val app = application as KaraokeApp
        app.appScope.launch(Dispatchers.IO) {
            app.storageManager.refreshLocalSongs()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val localizedContext = com.kapp.karaoke.util.LocaleHelper.applyLocale(newBase)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent the phone screen from auto-locking while the Karaoke app is active
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as KaraokeApp

        checkAndRequestPermissions()

        // Scan the KApp download folder immediately on app open to populate the local library
        app.appScope.launch(Dispatchers.IO) {
            app.storageManager.refreshLocalSongs()
        }

        setContent {
            KaraokeTheme {
                var currentTab by remember { mutableStateOf(NavTab.PLAYER) }
                var isPlayerControlsVisible by remember { mutableStateOf(true) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = KaraokeBg,
                    bottomBar = {
                        // Hide bottom navigation bar when player controls are hidden in PLAYER tab
                        if (currentTab != NavTab.PLAYER || isPlayerControlsVisible) {
                            NavigationBar(
                                containerColor = KaraokeCard,
                                contentColor = KaraokeText
                            ) {
                            NavigationBarItem(
                                selected = currentTab == NavTab.PLAYER,
                                onClick = { currentTab = NavTab.PLAYER },
                                icon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
                                label = { Text(stringResource(R.string.tab_player)) },
                                modifier = Modifier.tvFocusBorder(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = KaraokePrimary,
                                    selectedTextColor = KaraokePrimary,
                                    unselectedIconColor = KaraokeTextDim,
                                    unselectedTextColor = KaraokeTextDim,
                                    indicatorColor = KaraokePrimary.copy(alpha = 0.2f)
                                )
                            )

                            NavigationBarItem(
                                selected = currentTab == NavTab.QUEUE,
                                onClick = { currentTab = NavTab.QUEUE },
                                icon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                                label = { Text(stringResource(R.string.tab_queue)) },
                                modifier = Modifier.tvFocusBorder(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = KaraokePrimary,
                                    selectedTextColor = KaraokePrimary,
                                    unselectedIconColor = KaraokeTextDim,
                                    unselectedTextColor = KaraokeTextDim,
                                    indicatorColor = KaraokePrimary.copy(alpha = 0.2f)
                                )
                            )

                            NavigationBarItem(
                                selected = currentTab == NavTab.SEARCH,
                                onClick = { currentTab = NavTab.SEARCH },
                                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                label = { Text(stringResource(R.string.tab_search)) },
                                modifier = Modifier.tvFocusBorder(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = KaraokePrimary,
                                    selectedTextColor = KaraokePrimary,
                                    unselectedIconColor = KaraokeTextDim,
                                    unselectedTextColor = KaraokeTextDim,
                                    indicatorColor = KaraokePrimary.copy(alpha = 0.2f)
                                )
                            )

                            NavigationBarItem(
                                selected = currentTab == NavTab.LIBRARY,
                                onClick = { currentTab = NavTab.LIBRARY },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                label = { Text(stringResource(R.string.tab_library)) },
                                modifier = Modifier.tvFocusBorder(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = KaraokePrimary,
                                    selectedTextColor = KaraokePrimary,
                                    unselectedIconColor = KaraokeTextDim,
                                    unselectedTextColor = KaraokeTextDim,
                                    indicatorColor = KaraokePrimary.copy(alpha = 0.2f)
                                )
                            )

                            NavigationBarItem(
                                selected = currentTab == NavTab.SETTINGS,
                                onClick = { currentTab = NavTab.SETTINGS },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text(stringResource(R.string.tab_settings)) },
                                modifier = Modifier.tvFocusBorder(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = KaraokePrimary,
                                    selectedTextColor = KaraokePrimary,
                                    unselectedIconColor = KaraokeTextDim,
                                    unselectedTextColor = KaraokeTextDim,
                                    indicatorColor = KaraokePrimary.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = KaraokeBg
                    ) {
                        when (currentTab) {
                            NavTab.PLAYER -> PlayerScreen(
                                playerManager = app.playerManager,
                                queueManager = app.queueManager,
                                areControlsVisible = isPlayerControlsVisible,
                                onToggleControls = { isPlayerControlsVisible = it }
                            )
                            NavTab.QUEUE -> QueueScreen(
                                queueManager = app.queueManager,
                                onNavigateToPlayer = { currentTab = NavTab.PLAYER }
                            )
                            NavTab.SEARCH -> SearchScreen(
                                queueManager = app.queueManager,
                                storageManager = app.storageManager,
                                youtubeExtractor = app.youtubeExtractor
                            )
                            NavTab.LIBRARY -> LocalLibraryScreen(
                                queueManager = app.queueManager,
                                storageManager = app.storageManager,
                                onNavigateToPlayer = { currentTab = NavTab.PLAYER }
                            )
                            NavTab.SETTINGS -> SettingsScreen(
                                storageManager = app.storageManager
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
