package com.wtm.musicmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wtm.musicmanager.pairing.PairingRepository
import com.wtm.musicmanager.ui.MusicManagerRoot
import com.wtm.musicmanager.ui.theme.MusicManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected by Hilt. We use this to hand the token from the deep
     * link straight into [PairingRepository.acceptDeepLink] before
     * the Compose tree comes up — this is why we need a one-shot
     * CoroutineScope rather than lifecycleScope (which doesn't exist
     * before onCreate finishes).
     */
    @Inject lateinit var pairingRepository: PairingRepository

    private val intentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On cold start, see if we already have a token on disk. If
        // so, this will transition state to Paired and the UI gates
        // past the pairing screen. restore() is a no-op if no token
        // exists.
        intentScope.launch {
            pairingRepository.restore()
        }
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
        // mm://pair?session=...&token=...&code=...&host=...&port=...
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "mm" || uri.host != "pair") return
        val token = uri.getQueryParameter("token") ?: return
        val sessionId = uri.getQueryParameter("session") ?: uri.getQueryParameter("session_id")
        // acceptDeepLink is synchronous: it just persists the token
        // and sets the StateFlow. No I/O. We can call it directly
        // from onCreate (and onNewIntent) without a coroutine.
        pairingRepository.acceptDeepLink(
            token = token,
            deviceName = sessionId?.let { "TV ($it)" } ?: "Paired via QR",
        )
    }
}
