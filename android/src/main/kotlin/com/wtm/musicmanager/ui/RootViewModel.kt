package com.wtm.musicmanager.ui

import androidx.lifecycle.ViewModel
import com.wtm.musicmanager.pairing.PairingRepository
import com.wtm.musicmanager.pairing.PairingState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny VM that just exposes [PairingRepository.state] so [MusicManagerRoot]
 * can decide whether to show the pairing overlay or the bottom-nav tabs.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    pairingRepository: PairingRepository,
) : ViewModel() {
    val pairingState: StateFlow<PairingState> = pairingRepository.state
}
