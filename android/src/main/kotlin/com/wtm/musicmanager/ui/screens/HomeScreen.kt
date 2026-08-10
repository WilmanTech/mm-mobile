package com.wtm.musicmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wtm.musicmanager.data.LibraryRepository
import com.wtm.musicmanager.db.Artist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Top-level "Home" tab. Mirrors the look of a Spotify-style landing:
 *  - "Tu biblioteca" stats header (tracks / artists / albums).
 *  - "Artistas" row with a horizontal grid of circular artist avatars.
 *
 * The HomeScreen stays light-weight — it doesn't try to be the
 * primary content surface. That role belongs to LibraryScreen; Home
 * is the "where do I start?" entry point.
 */
@Composable
fun HomeScreen(
    onArtistClick: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state = state, onArtistClick = onArtistClick)
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onArtistClick: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "MusicManager",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Tu música, en cualquier sitio",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LibrarySummaryCard(
            trackCount = state.trackCount,
            artistCount = state.artists.size,
            albumCount = state.albumCount,
        )

        if (state.artists.isNotEmpty()) {
            Text(
                text = "Artistas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ArtistRow(artists = state.artists, onArtistClick = onArtistClick)
        }
    }
}

@Composable
private fun LibrarySummaryCard(
    trackCount: Long,
    artistCount: Int,
    albumCount: Long,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatColumn(count = trackCount, label = "canciones")
            StatColumn(count = albumCount, label = "álbumes")
            StatColumn(count = artistCount.toLong(), label = "artistas")
        }
    }
}

@Composable
private fun StatColumn(count: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.formatCount(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Long.formatCount(): String = when {
    this >= 1000 -> "%.1fk".format(this / 1000.0)
    else -> this.toString()
}

@Composable
private fun ArtistRow(
    artists: List<Artist>,
    onArtistClick: (Long) -> Unit,
) {
    // LazyHorizontalGrid with fixed rows=1 gives us a horizontal
    // grid (rows = 1, columns adaptive). The user can swipe the row.
    LazyHorizontalGrid(
        rows = GridCells.Fixed(1),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        items(items = artists, key = { it.id }) { artist ->
            ArtistAvatar(artist = artist, onClick = { onArtistClick(artist.id) })
        }
    }
}

@Composable
private fun ArtistAvatar(artist: Artist, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class HomeUiState(
    val trackCount: Long = 0L,
    val albumCount: Long = 0L,
    val artists: List<Artist> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: LibraryRepository,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        repository.observeTrackCount(),
        repository.observeArtists(),
    ) { count, artists ->
        HomeUiState(
            trackCount = count,
            // AlbumCount comes from observePlaylists (which the search
            // screen already queries). For Home we keep this derived
            // from artists' track_count column — it's a quick proxy
            // that matches what the user sees in LibraryScreen.
            albumCount = artists.sumOf { it.album_count },
            artists = artists,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(),
    )
}
