package com.fakehifi.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fakehifi.detector.analysis.QualityResult
import com.fakehifi.detector.analysis.StereoResult
import com.fakehifi.detector.model.BitDepthResult
import com.fakehifi.detector.model.TrackInfo
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.model.Verdict

/**
 * Cached copy of a TrackResult, keyed by file path. `sizeBytes` +
 * `dateModifiedSec` act as a cheap change-detector: if either differs from
 * what MediaStore reports on the next scan, the cache entry is stale and the
 * file gets re-analyzed.
 */
@Entity(tableName = "track_results")
data class TrackResultEntity(
    @PrimaryKey val filePath: String,
    val uri: String,
    val title: String,
    val artist: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
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
    // Quality metrics
    val peakDb: Double = 0.0,
    val rmsDb: Double = 0.0,
    val dynamicRange: Double = 0.0,
    val clippedSamplesCount: Long = 0,
    val maxConsecutiveClipped: Int = 0,
    // Stereo metrics
    val hasJointStereoCollapse: Boolean = false,
    val sideToMidHighFreqRatio: Double = 1.0,
    val originalBitrateKbps: Int = 0,
    val isDeepScan: Boolean,
    val spectrumDbCsv: String, // comma-separated, downsampled magnitude-in-dB values
    val multiSpectrumsCsv: String = "", // pipe separated windows, comma separated bins
    val spectrumBinHz: Double
) {
    fun toTrackResult(): TrackResult = TrackResult(
        track = TrackInfo(uri, title, artist, filePath, mimeType, sizeBytes, dateModifiedSec, durationMs),
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        detectedCutoffHz = detectedCutoffHz,
        confidencePercent = confidencePercent,
        verdict = runCatching { Verdict.valueOf(verdict) }.getOrDefault(Verdict.UNKNOWN),
        reason = reason,
        bitDepthResult = BitDepthResult(bitDepthChecked, bitDepthLooksPadded, bitDepthZeroLowBytePercent),
        qualityResult = QualityResult(peakDb, rmsDb, dynamicRange, clippedSamplesCount, maxConsecutiveClipped),
        stereoResult = StereoResult(hasJointStereoCollapse, sideToMidHighFreqRatio, 0),
        originalBitrateKbps = originalBitrateKbps,
        isDeepScan = isDeepScan,
        spectrumDb = if (spectrumDbCsv.isBlank()) emptyList()
            else spectrumDbCsv.split(",").mapNotNull { it.toDoubleOrNull() },
        multiSpectrums = if (multiSpectrumsCsv.isBlank()) emptyList()
            else multiSpectrumsCsv.split("|").map { window -> 
                window.split(",").mapNotNull { it.toDoubleOrNull() } 
            },
        spectrumBinHz = spectrumBinHz
    )

    companion object {
        fun fromTrackResult(result: TrackResult): TrackResultEntity = TrackResultEntity(
            filePath = result.track.filePath,
            uri = result.track.uri,
            title = result.track.title,
            artist = result.track.artist,
            mimeType = result.track.mimeType,
            sizeBytes = result.track.sizeBytes,
            dateModifiedSec = result.track.dateModifiedSec,
            durationMs = result.track.durationMs,
            sampleRateHz = result.sampleRateHz,
            bitDepth = result.bitDepth,
            detectedCutoffHz = result.detectedCutoffHz,
            confidencePercent = result.confidencePercent,
            verdict = result.verdict.name,
            reason = result.reason,
            bitDepthChecked = result.bitDepthResult?.checked ?: false,
            bitDepthLooksPadded = result.bitDepthResult?.looksPadded ?: false,
            bitDepthZeroLowBytePercent = result.bitDepthResult?.zeroLowBytePercent ?: 0,
            peakDb = result.qualityResult?.peakDb ?: 0.0,
            rmsDb = result.qualityResult?.rmsDb ?: 0.0,
            dynamicRange = result.qualityResult?.dynamicRange ?: 0.0,
            clippedSamplesCount = result.qualityResult?.clippedSamplesCount ?: 0,
            maxConsecutiveClipped = result.qualityResult?.maxConsecutiveClipped ?: 0,
            hasJointStereoCollapse = result.stereoResult?.hasJointStereoCollapse ?: false,
            sideToMidHighFreqRatio = result.stereoResult?.sideToMidHighFreqRatio ?: 1.0,
            originalBitrateKbps = result.originalBitrateKbps,
            isDeepScan = result.isDeepScan,
            spectrumDbCsv = result.spectrumDb.joinToString(",") { "%.1f".format(it) },
            multiSpectrumsCsv = result.multiSpectrums.joinToString("|") { window ->
                window.joinToString(",") { "%.1f".format(it) }
            },
            spectrumBinHz = result.spectrumBinHz
        )
    }
}
