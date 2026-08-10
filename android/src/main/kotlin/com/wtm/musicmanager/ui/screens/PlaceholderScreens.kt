package com.wtm.musicmanager.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// Phase 2.1: the SearchScreen used to live here as a placeholder. It's
// now real (com.wtm.musicmanager.ui.search.SearchScreen) and wired into
// the bottom-nav. SettingsScreen stays as a placeholder until Phase 3.

@Composable
fun SettingsScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge)
    }
}
