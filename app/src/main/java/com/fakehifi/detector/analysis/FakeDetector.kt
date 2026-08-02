package com.fakehifi.detector.analysis

import com.fakehifi.detector.model.BitDepthResult
import com.fakehifi.detector.model.ConfidenceContribution
import com.fakehifi.detector.model.MetadataMismatch
import com.fakehifi.detector.model.TrackInfo
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.model.Verdict

object FakeDetector {

    // Typical cutoffs left behind by common lossy encoders, in Hz.
    private const val MP3_192_CUTOFF = 18000
    private const val MP3_320_CUTOFF = 20000

    fun classify(
        track: TrackInfo,
        format: DecodedFormat,
        spectral: SpectralResult,
        bitDepthResult: BitDepthResult?,
        stereoResult: StereoResult? = null,
        qualityResult: QualityResult? = null,
        isDeepScan: Boolean
    ): TrackResult {
        val sampleRateHz = format.sampleRateHz
        val bitDepth = format.bitDepth

        if (sampleRateHz == 0 || spectral.cutoffHz == 0) {
            return TrackResult(
                track = track,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                detectedCutoffHz = 0,
                confidencePercent = 0,
                verdict = Verdict.UNKNOWN,
                reason = "Could not analyze this file (unsupported codec or decode error).",
                bitDepthResult = bitDepthResult,
                isDeepScan = isDeepScan,
                spectrumDb = emptyList(),
                multiSpectrums = emptyList(),
                spectrumBinHz = 0.0
            )
        }

        val cutoffHz = spectral.cutoffHz
        val slope = spectral.rolloffSlopeDbPerKhz
        var verdict: Verdict
        var reason: String

        val estimatedBitrate = estimateOriginalBitrate(cutoffHz)

        // Content-Aware Bandwidth Targets
        // If the spectral centroid is low (indicating "dark" content like cello, 
        // ambient, or low-pass mastered tracks), we scale the target bandwidth 
        // down to avoid false positives.
        val centroidRatio = (spectral.spectralCentroidHz.toDouble() / 5000.0).coerceIn(0.7, 1.0)
        val baseTarget = if (sampleRateHz <= 48000) 18800 else 24000
        val targetGenuineHz = (baseTarget * centroidRatio).toInt()

        val breakdown = mutableListOf<ConfidenceContribution>()
        
        // 1. Spectral Analysis Contribution (Capped at 30%)
        val rawSpectralBonus = spectral.slopeBonus - spectral.consistencyPenalty - spectral.sampleSizePenalty
        val spectralContribution = rawSpectralBonus.coerceIn(-30, 30)
        
        breakdown.add(ConfidenceContribution("Spectral Analysis", spectralContribution, "Based on slope, consistency, and sample size"))

        if (cutoffHz < targetGenuineHz - 2000) {
            // Informational only, doesn't affect confidence directly here as it's part of the verdict decision
            breakdown.add(ConfidenceContribution("Spectral Bandwidth", 0, "Lower than expected bandwidth for this content type (${cutoffHz / 1000.0} kHz)"))
        }

        if (spectral.curvatureScore > 15.0) {
            breakdown.add(ConfidenceContribution("Spectral Curvature", -10, "Digital 'shoulder' detected near cutoff (prototype metric)"))
        }

        // 2. Metadata Verification Contribution (Capped at 15%)
        var metadataMismatch = MetadataMismatch(false)
        var metadataContribution = 0
        if (format.declaredBitDepth > 0 && bitDepth < format.declaredBitDepth) {
            metadataMismatch = MetadataMismatch(true, "Declared ${format.declaredBitDepth}-bit, but physically decoded as ${bitDepth}-bit")
            metadataContribution = -15
            breakdown.add(ConfidenceContribution("Metadata Mismatch", metadataContribution, "Container claims higher bit-depth than physical stream"))
        } else if (format.declaredSampleRateHz > 0 && sampleRateHz != format.declaredSampleRateHz) {
            metadataMismatch = MetadataMismatch(true, "Declared ${format.declaredSampleRateHz}Hz, but physically decoded as ${sampleRateHz}Hz")
            metadataContribution = -10
            breakdown.add(ConfidenceContribution("Metadata Mismatch", metadataContribution, "Container sample rate does not match decoded stream"))
        }

        // Initialize confidence with base and core contributions
        var confidence = (60 + spectralContribution + metadataContribution).coerceIn(10, 90)

        when {
            // 1. Clear lossy cutoffs (below ~19kHz) with sharp brick-wall
            cutoffHz <= MP3_192_CUTOFF + 1000 && slope > 25 -> {
                verdict = Verdict.FAKE
                reason = "Spectrum ends sharply at ${cutoffHz / 1000.0}kHz (slope ${slope.toInt()}dB/kHz). This " +
                    "strongly aligns with empirically established lossy encoder profiles (~128-192 kbps)."
            }
            // 2. High-bitrate lossy (320kbps) signature: sharp ~20kHz cutoff
            cutoffHz <= MP3_320_CUTOFF + 500 && (slope > 35 || (slope > 25 && spectral.curvatureScore > 20.0)) -> {
                verdict = Verdict.FAKE
                reason = "Sheer brick-wall cutoff at ~20kHz (slope ${slope.toInt()}dB/kHz). This " +
                    "strongly aligns with high-bitrate lossy encoder profiles (~320 kbps)."
            }
            // 3. Genuine full-spectrum or High-Res bandwidth
            cutoffHz >= targetGenuineHz -> {
                if (confidence < 40) {
                    verdict = Verdict.SUSPICIOUS
                    reason = "Spectrum reaches ${cutoffHz / 1000.0}kHz but the signal is weak or " +
                        "inconsistent — result is low-confidence."
                } else {
                    verdict = Verdict.GENUINE
                    reason = "Spectral content reaches ${cutoffHz / 1000.0}kHz with a " +
                        (if (slope < 20) "natural roll-off" else "healthy slope") +
                        ", consistent with genuine ${qualityLabel(sampleRateHz, bitDepth)} audio."
                }
            }
            // 4. Close to genuine ("Likely Genuine") with natural decay
            cutoffHz >= targetGenuineHz - 3000 && slope < 22 -> {
                verdict = Verdict.GENUINE
                reason = "Spectrum rolls off gradually at ${cutoffHz / 1000.0}kHz (slope ${slope.toInt()}dB/kHz) — " +
                    "consistent with a high-quality acoustic recording."
            }
            // 5. Significant gap but gradual decay (maybe legitimate but dark mastering)
            cutoffHz < targetGenuineHz && slope < 12 -> {
                verdict = Verdict.SUSPICIOUS
                reason = "Spectrum rolls off very early at ${cutoffHz / 1000.0}kHz, but the decay is " +
                    "gradual. Could be a dark master or heavy analog filtering."
            }
            // 6. Generic suspicious (significant gap + unnatural drop)
            else -> {
                verdict = Verdict.SUSPICIOUS
                reason = "Spectrum ends at ${cutoffHz / 1000.0}kHz with an unnatural drop-off — " +
                    "indicates a potential transcode (~$estimatedBitrate kbps) or digital processing artifact."
            }
        }

        // 3. Stereo Integrity Contribution (Capped at 20%)
        if (stereoResult != null && stereoResult.hasJointStereoCollapse) {
            val ratioPercent = (stereoResult.sideToMidHighFreqRatio * 100).toInt()
            reason = "$reason Also, detected a 'Joint Stereo Collapse' artifact: Side-channel high-frequency energy " +
                    "is only $ratioPercent% of Mid energy, a strong signature of lossy codecs."
            
            val stereoContribution = stereoResult.confidencePenalty.coerceAtMost(20)
            breakdown.add(ConfidenceContribution("Stereo Integrity", stereoContribution, "Side-channel high-frequency energy collapse"))
            
            confidence = (confidence + stereoContribution).coerceAtMost(99)
            verdict = when (verdict) {
                Verdict.GENUINE -> Verdict.SUSPICIOUS
                Verdict.SUSPICIOUS -> Verdict.FAKE
                else -> verdict
            }
        }

        // 4. Bit-Depth Audit Contribution (Capped at 15%)
        if (bitDepth > 16) {
            if (bitDepthResult != null && bitDepthResult.checked) {
                if (bitDepthResult.looksPadded) {
                    reason = "$reason Also, ${bitDepthResult.zeroLowBytePercent}% of the lowest byte in " +
                            "this ${bitDepth}-bit file is exactly zero — the extra bit depth appears to carry no real information."
                    
                    val bitDepthContribution = 15
                    breakdown.add(ConfidenceContribution("Bit-Depth Audit", bitDepthContribution, "Lowest byte is mostly zero-padded"))
                    
                    confidence = (confidence + bitDepthContribution).coerceAtMost(99)
                    verdict = when (verdict) {
                        Verdict.GENUINE -> Verdict.SUSPICIOUS
                        Verdict.SUSPICIOUS -> Verdict.FAKE
                        else -> verdict
                    }
                }
            } else {
                // Document limitation: decoder provided float or truncated samples.
                reason = "$reason Note: Bit-depth padding audit was bypassed (decoder limitation for this format)."
                
                val bitDepthPenalty = -5
                breakdown.add(ConfidenceContribution("Format Audit", bitDepthPenalty, "Bit-depth padding check was bypassed"))
                
                // Lower the weight of the result by slightly reducing confidence 
                // because we are missing one independent signal.
                confidence = (confidence + bitDepthPenalty).coerceAtLeast(5)
            }
        }

        if (isDeepScan) {
            reason = "$reason (deep scan, ${AudioDecoder.DEEP_SCAN_WINDOW_COUNT} windows analyzed)"
            
            val deepScanBonus = 10
            breakdown.add(ConfidenceContribution("Deep Scan", deepScanBonus, "Extended analysis window count bonus"))
            
            confidence = (confidence + deepScanBonus).coerceAtMost(99)
        }

        return TrackResult(
            track = track,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            detectedCutoffHz = cutoffHz,
            confidencePercent = confidence,
            verdict = verdict,
            reason = reason,
            bitDepthResult = bitDepthResult,
            qualityResult = qualityResult,
            originalBitrateKbps = if (verdict == Verdict.FAKE || verdict == Verdict.SUSPICIOUS) estimatedBitrate else 0,
            isDeepScan = isDeepScan,
            spectrumDb = spectral.spectrumDb,
            multiSpectrums = spectral.multiSpectrums,
            spectrumBinHz = spectral.binHz,
            confidenceBreakdown = breakdown,
            metadataMismatch = metadataMismatch
        )
    }

    private fun estimateOriginalBitrate(cutoffHz: Int): Int {
        return when {
            cutoffHz <= 11500 -> 64
            cutoffHz <= 13500 -> 96
            cutoffHz <= 16500 -> 128
            cutoffHz <= 17500 -> 160
            cutoffHz <= 18500 -> 192
            cutoffHz <= 19500 -> 256
            else -> 320
        }
    }

    private fun qualityLabel(sampleRateHz: Int, bitDepth: Int): String {
        return if (sampleRateHz > 48000 || bitDepth > 16)
            "Hi-Res (${sampleRateHz / 1000}kHz/${bitDepth}-bit)"
        else
            "CD-quality lossless (${sampleRateHz / 1000}kHz/${bitDepth}-bit)"
    }
}
