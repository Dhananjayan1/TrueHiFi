package com.fakehifi.detector.model

import com.fakehifi.detector.analysis.ComponentResult
import com.fakehifi.detector.analysis.QualityResult
import com.fakehifi.detector.analysis.StereoResult

enum class Verdict { GENUINE, SUSPICIOUS, FAKE, UNKNOWN }

enum class SortOrder { TITLE_A_TO_Z, LATEST_FIRST, VERDICT }

data class TrackInfo(
    val uri: String,
    val title: String,
    val artist: String,
    val filePath: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val durationMs: Long
)

/**
 * Result of the low-order-bit check on files claiming >16-bit depth. Only
 * meaningful when the decoder actually gave us float/high-precision PCM
 * (see AudioDecoder) - otherwise `checked` is false and this is ignored.
 */
data class BitDepthResult(
    val checked: Boolean,
    val looksPadded: Boolean,
    val zeroLowBytePercent: Int
) : ComponentResult

data class ConfidenceContribution(
    val label: String,
    val scoreChange: Int, // e.g. +15 or -10
    val message: String,
    val isPositive: Boolean = scoreChange >= 0
)

data class MetadataMismatch(
    val hasMismatch: Boolean,
    val detail: String = ""
)

data class TrackResult(
    val track: TrackInfo,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val detectedCutoffHz: Int,
    val confidencePercent: Int,
    val verdict: Verdict,
    val reason: String,
    val bitDepthResult: BitDepthResult? = null,
    val qualityResult: QualityResult? = null,
    val stereoResult: StereoResult? = null,
    val originalBitrateKbps: Int = 0, // Estimated original lossy bitrate (if fake/suspicious)
    val isDeepScan: Boolean = false,
    val spectrumDb: List<Double> = emptyList(), // downsampled, for the detail-screen plot
    val multiSpectrums: List<List<Double>> = emptyList(), // overlay data
    val spectrumBinHz: Double = 0.0,
    val confidenceBreakdown: List<ConfidenceContribution> = emptyList(),
    val metadataMismatch: MetadataMismatch = MetadataMismatch(false)
)
