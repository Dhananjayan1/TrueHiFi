package com.fakehifi.detector.repository

import com.fakehifi.detector.model.ScanUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide holder for scan progress/results. The foreground service
 * writes to this while it works; the UI (via ScanViewModel) just observes
 * it. Keeping this outside both the Service and the ViewModel means scan
 * progress survives activity recreation and isn't tied to either's
 * lifecycle.
 */
object ScanRepository {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun update(transform: (ScanUiState) -> ScanUiState) {
        _uiState.update(transform)
    }
}
