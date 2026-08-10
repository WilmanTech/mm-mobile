package com.wtm.musicmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Real Settings screen — replaces the Phase 2 placeholder.
 *
 * Sections:
 *  1. Dispositivo — device name + library size
 *  2. Sincronización — last sync server time + status + "Sincronizar
 *     ahora" button (full sync, wipes + reload) and "Sync incremental"
 *     button (delta-only, faster, default for routine refreshes)
 *  3. Cuenta — "Desvincular este dispositivo" button
 *  4. Acerca de — version, build info
 *
 * No NavController involvement; the screen only writes back through
 * [SettingsViewModel.onUnpair] (which clears the token and resets
 * PairingState to Idle — the parent gate in MusicManagerRoot then
 * bounces to the PairingScreen automatically).
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    downloadViewModel: com.wtm.musicmanager.ui.downloads.DownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The download trigger is provided by MusicManagerRoot. We don't
    // thread it as a constructor arg to SettingsViewModel because
    // KSP would error on the second @Inject param (same gotcha as
    // AlbumDetailViewModel — verified 2026-08-10).
    androidx.compose.runtime.LaunchedEffect(downloadViewModel) {
        viewModel.bindDownloadTrigger(downloadViewModel)
    }
    // Refresh the trackCount once the screen mounts.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.onTrackCountLoaded(0L) // placeholder — Phase 4 will wire observeTrackCount
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "device") {
            SettingsSection(
                title = "Dispositivo",
                icon = Icons.Default.Devices,
            ) {
                KeyValueRow(
                    key = "Nombre",
                    value = state.deviceName ?: "Sin emparejar",
                )
                HorizontalDivider()
                KeyValueRow(
                    key = "Canciones en biblioteca",
                    value = state.trackCount.toString(),
                )
            }
        }

        item(key = "sync") {
            Spacer(Modifier.height(8.dp))
            SettingsSection(
                title = "Sincronización",
                icon = Icons.Default.CloudSync,
            ) {
                KeyValueRow(
                    key = "Última sync",
                    value = state.lastSyncServerTime ?: "Nunca",
                )
                if (state.lastSyncError != null) {
                    HorizontalDivider()
                    Text(
                        text = "Error: ${state.lastSyncError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(
                    label = "Sincronizar ahora",
                    description = "Borra la caché y descarga la library completa del servidor.",
                    enabled = !state.isSyncing,
                    inProgress = state.isSyncing,
                    onClick = { scope.launch { viewModel.onFullSync() } },
                )
                HorizontalDivider()
                ActionRow(
                    label = "Sync incremental",
                    description = "Solo descarga cambios desde la última sync (más rápido).",
                    enabled = !state.isSyncing,
                    inProgress = state.isSyncing,
                    onClick = { scope.launch { viewModel.onIncrementalSync() } },
                )
            }
        }

        item(key = "account") {
            Spacer(Modifier.height(8.dp))
            SettingsSection(
                title = "Cuenta",
                icon = Icons.Default.Logout,
            ) {
                ActionRow(
                    label = "Desvincular este dispositivo",
                    description = "Borra el token. Tendrás que volver a emparejar con el escritorio.",
                    enabled = true,
                    inProgress = false,
                    destructive = true,
                    onClick = { viewModel.onUnpair() },
                )
            }
        }

        item(key = "storage") {
            Spacer(Modifier.height(8.dp))
            SettingsSection(
                title = "Almacenamiento",
                icon = null,
            ) {
                KeyValueRow(
                    key = "Canciones descargadas",
                    value = "${state.downloadedTrackCount} / ${state.trackCount}",
                )
                ActionRow(
                    label = "Borrar descargas",
                    description = "Elimina todos los archivos en /cache/files",
                    enabled = state.downloadedTrackCount > 0L,
                    inProgress = false,
                    onClick = { viewModel.onClearAllDownloads() },
                    destructive = true,
                )
            }
        }

        item(key = "about") {
            Spacer(Modifier.height(8.dp))
            SettingsSection(
                title = "Acerca de",
                icon = null,
            ) {
                KeyValueRow(key = "Versión", value = "0.3.0")
                KeyValueRow(key = "Build", value = "Phase 3.C")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    description: String,
    enabled: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
