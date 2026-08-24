package com.wtm.musicmanager.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wtm.musicmanager.pairing.PairingState

/**
 * Full-screen pairing entry. Phase 2: manual host/port entry + start
 * button. Phase 3+ will add QR scanning (the CameraX + ML Kit deps are
 * already in build.gradle.kts from Fase 0).
 */
@Composable
fun PairingScreen(
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Emparejar con MusicManager",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Introduce la dirección del servidor MusicManager de tu escritorio.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Puerto") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = viewModel::onStartPairing,
                enabled = state.pairing !is PairingState.Pending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Iniciar emparejamiento")
            }

            Spacer(Modifier.height(24.dp))
            PairingStatusBlock(state)
        }
    }
}

@Composable
private fun PairingStatusBlock(state: PairingUiState) {
    when (val p = state.pairing) {
        PairingState.Idle -> state.statusMessage?.let { StatusLine(it) }
        is PairingState.Pending -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusLine("Código de emparejamiento")
            Spacer(Modifier.height(8.dp))
            Text(
                text = p.code,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Expira en ${formatRemaining(p.expiresAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PairingState.Paired -> {
            StatusLine("Emparejado como «${p.deviceName}»")
        }
        is PairingState.Expired -> StatusLine("El código expiró. Vuelve a intentarlo.")
        is PairingState.Revoked -> StatusLine("La sesión fue revocada.")
        is PairingState.Error -> StatusLine("Error HTTP ${p.httpStatus}")
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatRemaining(epochMs: Long): String {
    val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    val diffSec = ((epochMs - now) / 1000L).coerceAtLeast(0L)
    val min = diffSec / 60
    val sec = diffSec % 60
    return "%d:%02d".format(min, sec)
}
