package com.fakehifi.detector.analysis

import com.fakehifi.detector.model.TrackInfo

/**
 * Shared context containing all decoded audio data required for analysis.
 * This avoids redundant data passing and ensures consistency across components.
 */
data class AudioContext(
    val track: TrackInfo,
    val format: DecodedFormat,
    val windows: List<FloatArray>, // mono/left channel
    val integerWindows: List<IntArray>?, // raw PCM for LSB audit
    val stereoWindows: List<StereoWindow>?, // channel pairs for M/S analysis
    val isDeepScan: Boolean
)

/**
 * Marker interface for result objects returned by specific analysis components.
 */
interface ComponentResult

/**
 * Base interface for all modular audio analysis components.
 */
interface AudioAnalyzerComponent {
    suspend fun analyze(context: AudioContext): ComponentResult
}
