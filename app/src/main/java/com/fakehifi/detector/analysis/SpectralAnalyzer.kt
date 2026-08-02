package com.fakehifi.detector.analysis

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class SpectralResult(
    val cutoffHz: Int,
    val confidencePercent: Int,
    val rolloffSlopeDbPerKhz: Double,
    val binHz: Double,
    val spectrumDb: List<Double>, // Average spectrum
    val multiSpectrums: List<List<Double>> = emptyList(), // Spectrums for individual windows
    val baseScore: Int = 0,
    val slopeBonus: Int = 0,
    val consistencyPenalty: Int = 0,
    val sampleSizePenalty: Int = 0
) : ComponentResult

object SpectralAnalyzer : AudioAnalyzerComponent {

    private const val FFT_SIZE = 16384 // ~2.7 Hz resolution at 44.1kHz
    private const val PLOT_POINTS = 220

    override suspend fun analyze(context: AudioContext): SpectralResult {
        val windows = context.windows
        val sampleRateHz = context.format.sampleRateHz
        
        val usable = windows.filter { it.size >= FFT_SIZE }
        if (usable.isEmpty() || sampleRateHz <= 0) return SpectralResult(0, 0, 0.0, 0.0, emptyList(), emptyList())

        // Time-Domain Silence Detection
        val windowRmsDb = usable.map { calculateRmsDb(it) }
        val sortedRms = windowRmsDb.sorted()
        val medianRmsDb = sortedRms[sortedRms.size / 2]

        val binHz = sampleRateHz.toDouble() / FFT_SIZE
        val accumulated = DoubleArray(FFT_SIZE / 2)
        val perWindowCutoffs = mutableListOf<Int>()
        val multiSpectrums = mutableListOf<List<Double>>()
        var accumulatedCount = 0

        for (i in usable.indices) {
            val window = usable[i]
            val rmsDb = windowRmsDb[i]

            // Relative Gate: Skip silence chunks (-60dB below median) to save CPU (bypass FFT)
            if (rmsDb < medianRmsDb - 60.0) continue

            val mag = magnitudeSpectrum(window)
            for (j in mag.indices) accumulated[j] += mag[j]
            perWindowCutoffs.add(findCutoffHz(mag, binHz))
            accumulatedCount++
            
            // Collect individual window spectrums for visualization
            val magDb = DoubleArray(mag.size) { k -> 20 * log10(max(mag[k], 1e-9)) }
            multiSpectrums.add(downsample(magDb, PLOT_POINTS))
        }

        if (accumulatedCount == 0) {
            // All windows were silence or track is pure digital zero
            return SpectralResult(0, 0, 0.0, 0.0, emptyList(), emptyList())
        }

        for (i in accumulated.indices) accumulated[i] = accumulated[i] / accumulatedCount

        val avgCutoffHz = findCutoffHz(accumulated, binHz)
        val avgMagDb = DoubleArray(accumulated.size) { i -> 20 * log10(max(accumulated[i], 1e-9)) }

        val slope = calculateRolloffSlope(avgMagDb, avgCutoffHz, binHz)

        // ... [rest of the confidence logic remains same] ...
        val meanOfWindowCutoffs = perWindowCutoffs.average()
        val variance = perWindowCutoffs.sumOf {
            (it - meanOfWindowCutoffs) * (it - meanOfWindowCutoffs)
        } / perWindowCutoffs.size
        val stdDev = sqrt(variance)

        val slopeBonus = transitionSteepnessScore(slope)
        val consistencyPenalty = (stdDev / 2000.0 * 40).toInt().coerceIn(0, 40)
        val sampleSizePenalty = ((6 - accumulatedCount).coerceAtLeast(0) * 5)

        val baseScore = 60
        val confidence = (baseScore + slopeBonus - consistencyPenalty - sampleSizePenalty).coerceIn(5, 99)

        return SpectralResult(
            cutoffHz = avgCutoffHz,
            confidencePercent = confidence,
            rolloffSlopeDbPerKhz = slope,
            binHz = binHz,
            spectrumDb = downsample(avgMagDb, PLOT_POINTS),
            multiSpectrums = multiSpectrums,
            baseScore = baseScore,
            slopeBonus = slopeBonus,
            consistencyPenalty = consistencyPenalty,
            sampleSizePenalty = sampleSizePenalty
        )
    }

    private fun calculateRmsDb(window: FloatArray): Double {
        var sumSquares = 0.0
        var count = 0
        for (s in window) {
            if (!s.isNaN()) {
                sumSquares += (s * s).toDouble()
                count++
            }
        }
        val rms = if (count > 0) sqrt(sumSquares / count) else 0.0
        return 20 * log10(max(rms, 1e-12))
    }

    private fun magnitudeSpectrum(window: FloatArray): DoubleArray {
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val hann = 0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (FFT_SIZE - 1)))
            val sample = if (window[i].isNaN()) 0f else window[i]
            real[i] = sample * hann
        }
        FFT.transform(real, imag)
        return DoubleArray(FFT_SIZE / 2) { i -> sqrt(real[i] * real[i] + imag[i] * imag[i]) }
    }

    private fun findCutoffHz(magnitudeLinear: DoubleArray, binHz: Double): Int {
        val magDb = DoubleArray(magnitudeLinear.size) { i -> 20 * log10(max(magnitudeLinear[i], 1e-9)) }

        // Empirical Noise Floor Calculation
        // We establish the noise floor by calculating the median energy specifically 
        // in the 20kHz–22kHz range (the standard noise floor band). This avoids 
        // busy signal in lower frequencies artificially raising the floor.
        val floorStartBin = (20000 / binHz).toInt().coerceIn(0, magDb.size - 1)
        val floorEndBin = (22000 / binHz).toInt().coerceIn(floorStartBin, magDb.size - 1)
        
        val noiseFloorDb = if (floorEndBin > floorStartBin) {
            val floorBins = magDb.sliceArray(floorStartBin..floorEndBin).sortedArray()
            floorBins[floorBins.size / 2] + 15.0 // Median + 15dB margin
        } else {
            -85.0 // Fallback for low sample rates
        }

        val sortedMag = magDb.sortedArray()
        val quartileSize = (sortedMag.size * 0.25).toInt().coerceAtLeast(1)
        val topQuartileAvg = sortedMag.sliceArray(sortedMag.size - quartileSize until sortedMag.size).average()

        // Empirical threshold: if the loudest part of the spectrum is lower than -100dB, 
        // the signal is functionally silent.
        if (topQuartileAvg < -100) return 0

        val midEnd = (4000 / binHz).toInt().coerceIn(0, magDb.size)

        // Bandwidth-Based Signal Detection
        // Instead of hardcoded bin counts, we require a physical bandwidth of 50 Hz.
        // This ensures the detection logic is independent of the FFT size.
        val requiredConsecutiveBins = ceil(50.0 / binHz).toInt().coerceAtLeast(1)
        val windowBins = requiredConsecutiveBins
        var cutoffBin = 0
        
        for (i in (magDb.size - 1) downTo (midEnd + windowBins)) {
            var windowSum = 0.0
            for (j in 0 until windowBins) {
                windowSum += magDb[i - j]
            }
            if (windowSum / windowBins > noiseFloorDb) {
                cutoffBin = i
                break
            }
        }
        return (cutoffBin * binHz).toInt()
    }

    /**
     * Measures the average dB drop per kHz in the 2kHz window leading up to 
     * the cutoff. High values indicate a sharp roll-off matching empirically 
     * derived slope thresholds from lossy encoder corpus testing.
     */
    private fun calculateRolloffSlope(magDb: DoubleArray, cutoffHz: Int, binHz: Double): Double {
        if (cutoffHz <= 2000) return 0.0
        val cutoffBin = (cutoffHz / binHz).toInt().coerceIn(0, magDb.size - 1)
        val startBin = ((cutoffHz - 2000) / binHz).toInt().coerceIn(0, cutoffBin)
        if (startBin == cutoffBin) return 0.0
        
        val drop = magDb[startBin] - magDb[cutoffBin]
        return drop / 2.0 // dB per kHz (since we checked a 2kHz window)
    }

    /** Higher score = sharper cliff at the cutoff matching empirically 
     * derived slope thresholds from lossy encoder corpus testing. */
    private fun transitionSteepnessScore(slopeDbPerKhz: Double): Int {
        // Empirical thresholds based on lossy encoder corpus testing.
        return (slopeDbPerKhz / 60.0 * 30).toInt().coerceIn(0, 30)
    }

    private fun downsample(magDb: DoubleArray, points: Int): List<Double> {
        if (magDb.size <= points) return magDb.toList()
        val chunk = magDb.size / points
        return (0 until points).map { p ->
            val start = p * chunk
            val end = (start + chunk).coerceAtMost(magDb.size)
            if (start >= end) magDb.last() else magDb.slice(start until end).average()
        }
    }
}
