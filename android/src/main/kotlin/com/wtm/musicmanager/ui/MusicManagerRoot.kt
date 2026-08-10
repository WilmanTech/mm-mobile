package com.wtm.musicmanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtm.musicmanager.pairing.PairingState
import com.wtm.musicmanager.ui.detail.AlbumDetailScreen
import com.wtm.musicmanager.ui.detail.ArtistDetailScreen
import com.wtm.musicmanager.ui.detail.PlaylistDetailScreen
import com.wtm.musicmanager.ui.library.LibraryScreen
import com.wtm.musicmanager.ui.pairing.PairingScreen
import com.wtm.musicmanager.ui.screens.HomeScreen
import com.wtm.musicmanager.ui.search.SearchScreen
import com.wtm.musicmanager.ui.settings.SettingsScreen

private enum class TopLevelTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("Inicio", Icons.Default.Home),
    Search("Buscar", Icons.Default.Search),
    Library("Biblioteca", Icons.Default.LibraryMusic),
    Settings("Ajustes", Icons.Default.Person),
}

/**
 * Detail overlay state. Only one detail can be open at a time (the
 * album/artist/playlist lists each have their own row click, but a
 * detail pushes any previous one out — there's no back stack beyond
 * "back to list").
 *
 * The id is the row's primary key (album.id, artist.id, playlist.id).
 * The matching ViewModel takes the id via SavedStateHandle and
 * fetches the metadata + child rows.
 */
private sealed interface DetailOverlay {
    data class Album(val id: Long) : DetailOverlay
    data class Artist(val id: Long) : DetailOverlay
    data class Playlist(val id: Long) : DetailOverlay
}

@Composable
fun MusicManagerRoot(rootViewModel: RootViewModel = hiltViewModel()) {
    val pairingState by rootViewModel.pairingState.collectAsStateWithLifecycle()

    // PairingScreen is the full-screen entry. Once the user is paired we
    // swap to the bottom-nav scaffold. Pending pairing states still show
    // the screen (with the code visible) so the user can complete it.
    if (pairingState !is PairingState.Paired) {
        PairingScreen()
        return
    }

    var tab by remember { mutableStateOf(TopLevelTab.Home) }
    var detail by remember { mutableStateOf<DetailOverlay?>(null) }

    // Tab-change dismisses any open detail. Otherwise tapping a
    // different tab while looking at an album would leave the detail
    // visible on top of the new tab — surprising.
    fun selectTab(t: TopLevelTab) {
        if (t != tab) detail = null
        tab = t
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                TopLevelTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { selectTab(entry) },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Detail overlay (album/artist/playlist) takes precedence
            // over the active tab. The bottom-nav stays visible so the
            // user can switch tabs to dismiss the detail (the
            // selectTab() call also clears detail).
            when (val d = detail) {
                is DetailOverlay.Album -> AlbumDetailScreen(onBack = { detail = null })
                is DetailOverlay.Artist -> ArtistDetailScreen(
                    onBack = { detail = null },
                    onAlbumClick = { detail = DetailOverlay.Album(it) },
                    // Phase 3.B will replace this with a NowPlaying open.
                    onTrackClick = { /* no-op until Phase 3.B */ },
                )
                is DetailOverlay.Playlist -> PlaylistDetailScreen(
                    onBack = { detail = null },
                    onTrackClick = { /* no-op until Phase 3.B */ },
                )
                null -> when (tab) {
                    TopLevelTab.Home -> HomeScreen()
                    TopLevelTab.Search -> SearchScreen(
                        onAlbumClick = { detail = DetailOverlay.Album(it) },
                        onArtistClick = { detail = DetailOverlay.Artist(it) },
                        onPlaylistClick = { detail = DetailOverlay.Playlist(it) },
                    )
                    TopLevelTab.Library -> LibraryScreen()
                    TopLevelTab.Settings -> SettingsScreen()
                }
            }
        }
    }
}
