package com.fakehifi.detector.db

import com.fakehifi.detector.analysis.QualityResult
import com.fakehifi.detector.analysis.StereoResult
import com.fakehifi.detector.model.BitDepthResult
import com.fakehifi.detector.model.TrackInfo
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.model.Verdict

/**
 * A lightweight projection of TrackResultEntity that excludes large CSV strings.
 * Used for the main list and filtering to keep memory usage low and scrolling smooth.
 */
data class TrackResultSummary(
    val filePath: String,
    val uri: String,
    val title: String,
    val artist: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val detectedCutoffHz: Int,
    val confidencePercent: Int,
    val verdict: String,
    val reason: String,
    val bitDepthChecked: Boolean,
    val bitDepthLooksPadded: Boolean,
    val bitDepthZeroLowBytePercent: Int,
    val peakDb: Double,
    val rmsDb: Double,
    val dynamicRange: Double,
    val clippedSamplesCount: Long,
    val maxConsecutiveClipped: Int,
    val hasJointStereoCollapse: Boolean,
    val sideToMidHighFreqRatio: Double,
    val stereoConfidencePenalty: Int,
    val originalBitrateKbps: Int,
    val isDeepScan: Boolean
) {
    fun toTrackResult(): TrackResult = TrackResult(
        track = TrackInfo(uri, title, artist, filePath, sizeBytes, dateAdded, durationMs),
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        detectedCutoffHz = detectedCutoffHz,
        confidencePercent = confidencePercent,
        verdict = runCatching { Verdict.valueOf(verdict) }.getOrDefault(Verdict.UNKNOWN),
        reason = reason,
        bitDepthResult = BitDepthResult(bitDepthChecked, bitDepthLooksPadded, bitDepthZeroLowBytePercent),
        qualityResult = QualityResult(peakDb, rmsDb, dynamicRange, clippedSamplesCount, maxConsecutiveClipped),
        stereoResult = StereoResult(hasJointStereoCollapse, sideToMidHighFreqRatio, stereoConfidencePenalty),
        originalBitrateKbps = originalBitrateKbps,
        isDeepScan = isDeepScan,
        // Spectrum data is NOT included in summary
        spectrumDb = emptyList(),
        multiSpectrums = emptyList(),
        spectrumBinHz = 0.0
    )
}
