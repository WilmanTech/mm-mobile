package com.wtm.musicmanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.wtm.musicmanager.ui.screens.HomeScreen
import com.wtm.musicmanager.ui.screens.LibraryScreen
import com.wtm.musicmanager.ui.screens.PairingScreen
import com.wtm.musicmanager.ui.screens.SearchScreen
import com.wtm.musicmanager.ui.screens.SettingsScreen

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
fun MusicManagerRoot() {
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

    // Pairing entry is modal-over-content, not part of the bottom nav.
    // Shown when no paired server is configured.
    PairingScreen()
}
