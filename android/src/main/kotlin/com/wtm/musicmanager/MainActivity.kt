package com.wtm.musicmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wtm.musicmanager.ui.MusicManagerRoot
import com.wtm.musicmanager.ui.theme.MusicManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePairingIntent(intent)
        setContent {
            MusicManagerTheme {
                MusicManagerRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    private fun handlePairingIntent(intent: Intent?) {
        // vm://pair?session=...&token=...&host=...&port=...
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "vm" || uri.host != "pair") return
        // PairingCoordinator (wired up in feature/pairing module) handles this
        // through a SharedFlow consumed by the pairing screen.
    }
}
