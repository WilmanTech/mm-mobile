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
import com.wtm.musicmanager.ui.screens.HomeScreen
import com.wtm.musicmanager.ui.screens.SearchScreen
import com.wtm.musicmanager.ui.screens.SettingsScreen
import com.wtm.musicmanager.ui.library.LibraryScreen
import com.wtm.musicmanager.ui.pairing.PairingScreen

private enum class TopLevelTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("Inicio", Icons.Default.Home),
    Search("Buscar", Icons.Default.Search),
    Library("Biblioteca", Icons.Default.LibraryMusic),
    Settings("Ajustes", Icons.Default.Person),
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

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                TopLevelTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                TopLevelTab.Home -> HomeScreen()
                TopLevelTab.Search -> SearchScreen()
                TopLevelTab.Library -> LibraryScreen()
                TopLevelTab.Settings -> SettingsScreen()
            }
        }
    }
}
