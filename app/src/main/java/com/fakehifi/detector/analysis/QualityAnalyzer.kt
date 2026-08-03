package com.fakehifi.detector.analysis

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class QualityResult(
    val peakDb: Double,
    val rmsDb: Double,
    val medianRmsDb: Double,
    val dynamicRange: Double, // Original crest factor
    val drRating: Int, // Standardized DR rating (1-20)
    val isBrickwalled: Boolean,
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
        if (windows.isEmpty()) return QualityResult(0.0, 0.0, 0.0, 0.0, 0, false, 0, 0)

        var peakLinear = 0f
        var sumSquaresGlobal = 0.0
        var totalSamplesGlobal = 0L
        var clippedCount = 0L
        var maxConsecutiveClipped = 0
        var currentConsecutiveClipped = 0
        
        val windowRmsValues = mutableListOf<Double>()

        for (window in windows) {
            var windowSumSquares = 0.0
            for (sample in window) {
                if (sample.isNaN()) continue
                
                val absSample = if (sample < 0) -sample else sample
                if (absSample > peakLinear) peakLinear = absSample
                
                windowSumSquares += (sample.toDouble() * sample.toDouble())
                sumSquaresGlobal += (sample.toDouble() * sample.toDouble())
                totalSamplesGlobal++

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
            if (window.isNotEmpty()) {
                windowRmsValues.add(sqrt(windowSumSquares / window.size))
            }
        }
        
        // Final check for the last window
        if (currentConsecutiveClipped > maxConsecutiveClipped) {
            maxConsecutiveClipped = currentConsecutiveClipped
        }

        val rmsLinearGlobal = if (totalSamplesGlobal > 0) sqrt(sumSquaresGlobal / totalSamplesGlobal) else 0.0
        val peakDb = 20 * log10(max(peakLinear.toDouble(), 1e-6))
        val rmsDbGlobal = 20 * log10(max(rmsLinearGlobal, 1e-6))
        
        // Median RMS: more robust to gaps/silence
        val medianRmsLinear = if (windowRmsValues.isNotEmpty()) {
            windowRmsValues.sorted()[windowRmsValues.size / 2]
        } else 0.0
        val medianRmsDb = 20 * log10(max(medianRmsLinear, 1e-6))
        
        // Dynamic Range (standardized DR Rating)
        // DR = Peak - Median RMS. We floor it to match common tools.
        val drExact = peakDb - medianRmsDb
        val drRating = drExact.toInt().coerceIn(1, 20)
        
        // Brick-walled: DR < 7 is generally considered compressed, < 5 is heavy
        val isBrickwalled = drRating < 7

        return QualityResult(
            peakDb = peakDb,
            rmsDb = rmsDbGlobal,
            medianRmsDb = medianRmsDb,
            dynamicRange = peakDb - rmsDbGlobal, // Keep original crest factor too
            drRating = drRating,
            isBrickwalled = isBrickwalled,
            clippedSamplesCount = clippedCount,
            maxConsecutiveClipped = maxConsecutiveClipped
        )
    }
}
