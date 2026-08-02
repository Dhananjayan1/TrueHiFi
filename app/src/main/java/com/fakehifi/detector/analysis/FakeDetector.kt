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
        var confidence = spectral.confidencePercent

        val estimatedBitrate = estimateOriginalBitrate(cutoffHz)

        // Empirical Bandwidth Targets
        val targetGenuineHz = if (sampleRateHz <= 48000) 19500 else 24000

        val breakdown = mutableListOf<ConfidenceContribution>()
        breakdown.add(ConfidenceContribution("Spectral Baseline", spectral.baseScore, "Initial analysis baseline"))

        if (cutoffHz < targetGenuineHz - 2000) {
            breakdown.add(ConfidenceContribution("Spectral Bandwidth", 0, "Sharp spectral cutoff detected at ${cutoffHz / 1000.0} kHz"))
        }

        if (spectral.slopeBonus > 0) {
            breakdown.add(ConfidenceContribution("Spectral Slope", spectral.slopeBonus, "Brick-wall spectral slope aligns with lossy profiles"))
        }
        if (spectral.consistencyPenalty > 0) {
            breakdown.add(ConfidenceContribution("Temporal Consistency", -spectral.consistencyPenalty, "Cutoff varies between analysis windows"))
        }
        if (spectral.sampleSizePenalty > 0) {
            breakdown.add(ConfidenceContribution("Sample Size", -spectral.sampleSizePenalty, "Insufficient audio data for high-confidence result"))
        }

        // Metadata Verification Cross-Check
        var metadataMismatch = MetadataMismatch(false)
        if (format.declaredBitDepth > 0 && bitDepth < format.declaredBitDepth) {
            metadataMismatch = MetadataMismatch(true, "Declared ${format.declaredBitDepth}-bit, but physically decoded as ${bitDepth}-bit")
            breakdown.add(ConfidenceContribution("Metadata Mismatch", -20, "Container claims higher bit-depth than physical stream supports"))
            confidence = (confidence - 20).coerceAtLeast(5)
        } else if (format.declaredSampleRateHz > 0 && sampleRateHz != format.declaredSampleRateHz) {
            metadataMismatch = MetadataMismatch(true, "Declared ${format.declaredSampleRateHz}Hz, but physically decoded as ${sampleRateHz}Hz")
            breakdown.add(ConfidenceContribution("Metadata Mismatch", -10, "Container sample rate does not match decoded stream"))
            confidence = (confidence - 10).coerceAtLeast(5)
        }

        when {
            // 1. Clear lossy cutoffs (below ~19kHz) with sharp brick-wall
            cutoffHz <= MP3_192_CUTOFF + 1000 && slope > 25 -> {
                verdict = Verdict.FAKE
                reason = "Spectrum ends sharply at ${cutoffHz / 1000.0}kHz (slope ${slope.toInt()}dB/kHz). This " +
                    "strongly aligns with empirically established lossy encoder profiles (~128-192 kbps) " +
                    "repackaged as ${qualityLabel(sampleRateHz, bitDepth)}."
            }
            // 2. High-bitrate lossy (320kbps) signature: sharp ~20kHz cutoff
            cutoffHz <= MP3_320_CUTOFF + 500 && slope > 35 -> {
                verdict = Verdict.FAKE
                reason = "Sheer brick-wall cutoff at ~20kHz (slope ${slope.toInt()}dB/kHz). This " +
                    "strongly aligns with empirically established lossy encoder profiles (~320 kbps) transcode."
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
            cutoffHz >= targetGenuineHz - 3000 && slope < 15 -> {
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

        // Independent signal: Joint Stereo Collapse (Codec artifact)
        if (stereoResult != null && stereoResult.hasJointStereoCollapse) {
            val ratioPercent = (stereoResult.sideToMidHighFreqRatio * 100).toInt()
            reason = "$reason Also, detected a 'Joint Stereo Collapse' artifact: Side-channel high-frequency energy " +
                    "is only $ratioPercent% of Mid energy, a strong signature of lossy codecs."
            
            breakdown.add(ConfidenceContribution("Stereo Integrity", stereoResult.confidencePenalty, "Side-channel high-frequency energy collapse"))
            
            confidence = (confidence + stereoResult.confidencePenalty).coerceAtMost(99)
            verdict = when (verdict) {
                Verdict.GENUINE -> Verdict.SUSPICIOUS
                Verdict.SUSPICIOUS -> Verdict.FAKE
                else -> verdict
            }
        }

        // Independent signal: claims >16-bit but the extra bits are silent/padded.
        if (bitDepth > 16) {
            if (bitDepthResult != null && bitDepthResult.checked) {
                if (bitDepthResult.looksPadded) {
                    reason = "$reason Also, ${bitDepthResult.zeroLowBytePercent}% of the lowest byte in " +
                            "this ${bitDepth}-bit file is exactly zero — the extra bit depth appears to carry no real information."
                    
                    breakdown.add(ConfidenceContribution("Bit-Depth Audit", 15, "Lowest byte is mostly zero-padded"))
                    
                    confidence = (confidence + 15).coerceAtMost(99)
                    verdict = when (verdict) {
                        Verdict.GENUINE -> Verdict.SUSPICIOUS
                        Verdict.SUSPICIOUS -> Verdict.FAKE
                        else -> verdict
                    }
                }
            } else {
                // Document limitation: decoder provided float or truncated samples.
                reason = "$reason Note: Bit-depth padding audit was bypassed (decoder limitation for this format)."
                
                breakdown.add(ConfidenceContribution("Format Audit", -5, "Bit-depth padding check was bypassed"))
                
                // Lower the weight of the result by slightly reducing confidence 
                // because we are missing one independent signal.
                confidence = (confidence - 5).coerceAtLeast(5)
            }
        }

        if (isDeepScan) {
            reason = "$reason (deep scan, ${AudioDecoder.DEEP_SCAN_WINDOW_COUNT} windows analyzed)"
            
            breakdown.add(ConfidenceContribution("Deep Scan", 10, "Extended analysis window count bonus"))
            
            confidence = (confidence + 10).coerceAtMost(99)
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
