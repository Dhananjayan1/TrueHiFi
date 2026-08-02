package com.fakehifi.detector.model

enum class ResultFilter { ALL, FAKE, SUSPICIOUS, GENUINE, UNKNOWN }

data class ScanUiState(
    val isScanning: Boolean = false,
    val totalTracks: Int = 0,
    val scannedTracks: Int = 0,
    val currentTitle: String = "",
    val results: List<TrackResult> = emptyList(),
    val filter: ResultFilter = ResultFilter.ALL,
    val sortOrder: SortOrder = SortOrder.VERDICT,
    val searchQuery: String = "",
    val selectedUris: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false
) {
    val visibleResults: List<TrackResult>
        get() {
            var filtered = when (filter) {
                ResultFilter.ALL -> results
                ResultFilter.FAKE -> results.filter { it.verdict == Verdict.FAKE }
                ResultFilter.SUSPICIOUS -> results.filter { it.verdict == Verdict.SUSPICIOUS }
                ResultFilter.GENUINE -> results.filter { it.verdict == Verdict.GENUINE }
                ResultFilter.UNKNOWN -> results.filter { it.verdict == Verdict.UNKNOWN }
            }

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                filtered = filtered.filter { 
                    it.track.title.lowercase().contains(q) || 
                    it.track.artist.lowercase().contains(q) ||
                    it.track.filePath.lowercase().contains(q)
                }
            }

            // Results are already sorted by the database layer.
            return filtered
        }
}
