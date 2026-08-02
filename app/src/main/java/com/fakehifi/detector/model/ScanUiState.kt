package com.fakehifi.detector.model

enum class ResultFilter { ALL, FAKE, SUSPICIOUS, GENUINE, UNKNOWN }

data class ScanUiState(
    val isScanning: Boolean = false,
    val totalTracks: Int = 0,
    val scannedTracks: Int = 0,
    val currentTitle: String = "",
    val results: List<TrackResult> = emptyList(),
    val filteredResults: List<TrackResult> = emptyList(),
    val fakeCount: Int = 0,
    val suspiciousCount: Int = 0,
    val filter: ResultFilter = ResultFilter.ALL,
    val sortOrder: SortOrder = SortOrder.VERDICT,
    val searchQuery: String = "",
    val selectedUris: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false
)
