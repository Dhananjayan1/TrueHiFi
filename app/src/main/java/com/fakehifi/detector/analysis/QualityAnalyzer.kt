package com.fakehifi.detector.analysis

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class QualityResult(
    val peakDb: Double,
    val rmsDb: Double,
    val dynamicRange: Double, // Crest factor in dB
    val clippedSamplesCount: Long,
    val maxConsecutiveClipped: Int
) : ComponentResult

object QualityAnalyzer : AudioAnalyzerComponent {

    /**
     * Analyzes PCM samples to calculate peak, RMS, dynamic range, and clipping.
     * Operates on normalized floats [-1.0, 1.0].
     */
    override suspend fun analyze(context: AudioContext): QualityResult {
        val windows = context.windows
        if (windows.isEmpty()) return QualityResult(0.0, 0.0, 0.0, 0, 0)

        var peakLinear = 0f
        var sumSquares = 0.0
        var totalSamples = 0L
        var clippedCount = 0L
        var maxConsecutiveClipped = 0
        var currentConsecutiveClipped = 0

        for (window in windows) {
            for (sample in window) {
                if (sample.isNaN()) continue
                
                val absSample = if (sample < 0) -sample else sample
                if (absSample > peakLinear) peakLinear = absSample
                
                sumSquares += (sample.toDouble() * sample.toDouble())
                totalSamples++

                // Clipping check: hits 0 dBFS (abs value >= 0.999...)
                if (absSample >= 0.999f) {
                    clippedCount++
                    currentConsecutiveClipped++
                } else {
                    if (currentConsecutiveClipped > maxConsecutiveClipped) {
                        maxConsecutiveClipped = currentConsecutiveClipped
                    }
                    currentConsecutiveClipped = 0
                }
            }
        }
        
        // Final check for the last window
        if (currentConsecutiveClipped > maxConsecutiveClipped) {
            maxConsecutiveClipped = currentConsecutiveClipped
        }

        val rmsLinear = if (totalSamples > 0) sqrt(sumSquares / totalSamples) else 0.0
        val peakDb = 20 * log10(max(peakLinear.toDouble(), 1e-6))
        val rmsDb = 20 * log10(max(rmsLinear, 1e-6))
        
        // Dynamic Range as Crest Factor (Peak - RMS in dB)
        val dr = peakDb - rmsDb

        return QualityResult(
            peakDb = peakDb,
            rmsDb = rmsDb,
            dynamicRange = dr,
            clippedSamplesCount = clippedCount,
            maxConsecutiveClipped = maxConsecutiveClipped
        )
    }
}
