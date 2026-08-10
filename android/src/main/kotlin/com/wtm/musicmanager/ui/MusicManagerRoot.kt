package com.wtm.musicmanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.wtm.musicmanager.ui.detail.AlbumDetailScreen
import com.wtm.musicmanager.ui.detail.ArtistDetailScreen
import com.wtm.musicmanager.ui.detail.PlaylistDetailScreen
import com.wtm.musicmanager.ui.downloads.DownloadViewModel
import com.wtm.musicmanager.ui.library.LibraryScreen
import com.wtm.musicmanager.ui.nowplaying.NowPlayingMini
import com.wtm.musicmanager.pairing.PairingState
import com.wtm.musicmanager.ui.nowplaying.NowPlayingMiniUi
import com.wtm.musicmanager.ui.nowplaying.NowPlayingMiniViewModel
import com.wtm.musicmanager.ui.pairing.PairingScreen
import com.wtm.musicmanager.ui.search.SearchScreen
import com.wtm.musicmanager.ui.screens.HomeScreen
import com.wtm.musicmanager.ui.settings.SettingsScreen

/**
 * Top-level router. Three layers of state:
 *
 *  1. Pairing gate — PairingScreen until AuthStorage has a token.
 *  2. Bottom-nav tabs (Home/Search/Library/Settings).
 *  3. Detail overlay (Album/Artist/Playlist) on top of the tab.
 *  4. (Phase 3.B) NowPlayingMini — persistent above the bottom-nav,
 *     hidden when PlayerState.Idle.
 *
 * Tab change clears the detail overlay (selectTab()). Detail
 * itself has a `onBack` callback that sets detail = null.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicManagerRoot(
    rootViewModel: RootViewModel = hiltViewModel(),
    nowPlayingViewModel: NowPlayingMiniViewModel = hiltViewModel(),
    downloadViewModel: DownloadViewModel = hiltViewModel(),
) {
    val pairingState = rootViewModel.pairingState

    if (pairingState !is PairingState.Paired) {
        // PairingScreen uses Hilt's default viewModel arg so we don't
        // need to thread a specific instance here.
        PairingScreen()
        return
    }

    var tab: TopLevelTab by rememberSaveable { mutableStateOf(TopLevelTab.Home) }
    var detail: DetailOverlay? by rememberSaveable(stateSaver = DetailOverlay.Saver) {
        mutableStateOf(null)
    }

    fun selectTab(t: TopLevelTab) {
        tab = t
        detail = null
    }

    Scaffold(
        bottomBar = {
            Box {
                // The mini-player is conditionally rendered. When
                // PlayerState goes Idle the bar unmounts entirely;
                // the bottom-nav jumps up by 64dp in that instant.
                // That's acceptable for the rare "user dismissed"
                // transition. For a smoother animation we'd wrap
                // this in a `AnimatedVisibility` — deferred since
                // it's a polish concern, not a feature.
                val mini = nowPlayingViewModel.state.value
                if (mini !is NowPlayingMiniUi.Hidden) {
                    NowPlayingMini(
                        // Tap → open full-screen NowPlaying (Phase 4).
                        // For now we just no-op — the bar is not
                        // strictly clickable but it visually responds
                        // to taps (Material ripple).
                        onClick = { /* phase 4 */ },
                    )
                }
                NavigationBar {
                    TopLevelTab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { selectTab(t) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val d = detail) {
                is DetailOverlay.Album -> AlbumDetailScreen(
                    onBack = { detail = null },
                    onTrackClick = { nowPlayingViewModel.play(it) },
                    downloadTrigger = downloadViewModel,
                )
                is DetailOverlay.Artist -> ArtistDetailScreen(
                    onBack = { detail = null },
                    onAlbumClick = { detail = DetailOverlay.Album(it) },
                    onTrackClick = { nowPlayingViewModel.play(it) },
                )
                is DetailOverlay.Playlist -> PlaylistDetailScreen(
                    onBack = { detail = null },
                    onTrackClick = { nowPlayingViewModel.play(it) },
                )
                null -> when (tab) {
                    TopLevelTab.Home -> HomeScreen(
                        onArtistClick = { detail = DetailOverlay.Artist(it) },
                    )
                    TopLevelTab.Search -> SearchScreen(
                        onAlbumClick = { detail = DetailOverlay.Album(it) },
                        onArtistClick = { detail = DetailOverlay.Artist(it) },
                        onPlaylistClick = { detail = DetailOverlay.Playlist(it) },
                        onTrackClick = { nowPlayingViewModel.play(it) },
                    )
                    TopLevelTab.Library -> LibraryScreen(
                        onPlayTrack = { nowPlayingViewModel.play(it) },
                    )
                    TopLevelTab.Settings -> SettingsScreen()
                }
            }
        }
    }
}

enum class TopLevelTab(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Default.Home),
    Search("Buscar", Icons.Default.Search),
    Library("Biblioteca", Icons.Default.LibraryMusic),
    Settings("Ajustes", Icons.Default.Settings),
}

/**
 * Per-tab top-level detail overlay. The id payload survives
 * process death via [Saver] so the user can land on a detail
 * screen after Android garbage-collected the Activity while
 * the screen was locked.
 */
sealed interface DetailOverlay {
    data class Album(val id: Long) : DetailOverlay
    data class Artist(val id: Long) : DetailOverlay
    data class Playlist(val id: Long) : DetailOverlay

    companion object {
        val Saver: Saver<DetailOverlay?, Any> = Saver(
            save = { value ->
                when (value) {
                    null -> "null"
                    is Album -> "album:${value.id}"
                    is Artist -> "artist:${value.id}"
                    is Playlist -> "playlist:${value.id}"
                }
            },
            restore = { encoded ->
                when (encoded) {
                    "null" -> null
                    else -> {
                        val (kind, id) = (encoded as String).split(":", limit = 2)
                        when (kind) {
                            "album" -> Album(id.toLong())
                            "artist" -> Artist(id.toLong())
                            "playlist" -> Playlist(id.toLong())
                            else -> null
                        }
                    }
                }
            },
        )
    }
}
