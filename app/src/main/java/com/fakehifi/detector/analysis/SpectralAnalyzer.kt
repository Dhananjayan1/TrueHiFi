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

        // Representative Window Selection:
        // Instead of just gating quiet windows, we pick a stratified sample that covers 
        // the full dynamic range (loud, medium, quiet) to ensure the algorithm sees 
        // codec behavior across all energy levels.
        val windowsWithRms = usable.map { it to calculateRmsDb(it) }
            .filter { it.second > -100.0 } // Exclude absolute digital zero
            .sortedByDescending { it.second }

        if (windowsWithRms.isEmpty()) return SpectralResult(0, 0, 0.0, 0.0, emptyList(), emptyList())

        val selectedIndices = mutableSetOf<Int>()
        // 1. Always include the loudest windows (best SNR for cutoff)
        selectedIndices.add(0)
        if (windowsWithRms.size > 1) selectedIndices.add(1)
        
        // 2. Include medium windows
        if (windowsWithRms.size > 3) {
            selectedIndices.add(windowsWithRms.size / 2)
            selectedIndices.add(windowsWithRms.size / 2 + 1)
        }
        
        // 3. Include quiet windows (where compression artifacts like ringing are most visible)
        if (windowsWithRms.size > 5) {
            selectedIndices.add(windowsWithRms.size - 1)
            selectedIndices.add(windowsWithRms.size - 2)
        } else if (windowsWithRms.size > 2) {
            selectedIndices.add(windowsWithRms.size - 1)
        }

        val representativeWindows = windowsWithRms.filterIndexed { index, _ -> selectedIndices.contains(index) }

        val binHz = sampleRateHz.toDouble() / FFT_SIZE
        val accumulated = DoubleArray(FFT_SIZE / 2)
        val windowMetadata = mutableListOf<Pair<Int, Double>>() // Cutoff, RMS
        val multiSpectrums = mutableListOf<List<Double>>()

        for ((window, rmsDb) in representativeWindows) {
            val mag = magnitudeSpectrum(window)
            for (j in mag.indices) accumulated[j] += mag[j]
            
            val cutoff = findCutoffHz(mag, binHz, sampleRateHz)
            windowMetadata.add(cutoff to rmsDb)
            
            // Collect individual window spectrums for visualization
            val magDb = DoubleArray(mag.size) { k -> 20 * log10(max(mag[k], 1e-9)) }
            multiSpectrums.add(downsample(magDb, PLOT_POINTS))
        }

        val processedCount = representativeWindows.size
        for (i in accumulated.indices) accumulated[i] = accumulated[i] / processedCount

        val avgCutoffHz = findCutoffHz(accumulated, binHz, sampleRateHz)
        val avgMagDb = DoubleArray(accumulated.size) { i -> 20 * log10(max(accumulated[i], 1e-9)) }

        val slope = calculateRolloffSlope(avgMagDb, avgCutoffHz, binHz)

        // Consistency logic refinement:
        // We only care about consistency among windows that actually have enough signal 
        // to detect a reliable cutoff. Quiet windows often fail this and shouldn't 
        // tank the whole file's confidence.
        val maxRms = windowMetadata.maxOfOrNull { it.second } ?: -100.0
        val reliableCutoffs = windowMetadata
            .filter { it.second > maxRms - 40.0 && it.first > 0 }
            .map { it.first }

        val stdDev = if (reliableCutoffs.size >= 2) {
            val mean = reliableCutoffs.average()
            val variance = reliableCutoffs.sumOf { (it - mean) * (it - mean) } / reliableCutoffs.size
            sqrt(variance)
        } else {
            0.0
        }

        val slopeBonus = transitionSteepnessScore(slope)
        val consistencyPenalty = (stdDev / 2000.0 * 40).toInt().coerceIn(0, 40)
        val sampleSizePenalty = ((6 - processedCount).coerceAtLeast(0) * 5)

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
        val numChunks = window.size / FFT_SIZE
        if (numChunks <= 0) return DoubleArray(FFT_SIZE / 2)

        val accumulated = DoubleArray(FFT_SIZE / 2)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)

        for (c in 0 until numChunks) {
            val offset = c * FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                val hann = 0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (FFT_SIZE - 1)))
                val sample = if (window[offset + i].isNaN()) 0f else window[offset + i]
                real[i] = sample.toDouble() * hann
                imag[i] = 0.0
            }
            FFT.transform(real, imag)
            for (i in 0 until FFT_SIZE / 2) {
                accumulated[i] += sqrt(real[i] * real[i] + imag[i] * imag[i])
            }
        }

        for (i in accumulated.indices) {
            accumulated[i] /= numChunks.toDouble()
        }
        return accumulated
    }

    private fun findCutoffHz(magnitudeLinear: DoubleArray, binHz: Double, sampleRateHz: Int): Int {
        val magDb = DoubleArray(magnitudeLinear.size) { i -> 20 * log10(max(magnitudeLinear[i], 1e-9)) }

        // Statistical Noise Floor Estimator:
        // We establish a robust noise floor by analyzing the distribution of bins across 
        // a broad frequency range (avoiding the lowest frequencies which often contain DC/hum).
        val analysisStartBin = (4000 / binHz).toInt().coerceIn(0, magDb.size - 1)
        val highBins = magDb.sliceArray(analysisStartBin until magDb.size).sortedArray()
        
        // Use the 5th percentile as a robust estimate for the noise floor.
        // This rejects peaks (musical content) and focuses on the underlying noise floor.
        val statisticalFloor = if (highBins.isNotEmpty()) {
            highBins[(highBins.size * 0.05).toInt()]
        } else -100.0

        // Band-specific Heuristic (Fallback for validation):
        val floorStartHz = (if (sampleRateHz <= 48000) 20000.0 else (sampleRateHz / 2.0) - 4000.0).coerceAtLeast(4000.0)
        val floorEndHz = (floorStartHz + 2000.0).coerceAtMost(sampleRateHz / 2.0 - 100.0)
        val floorStartBin = (floorStartHz / binHz).toInt().coerceIn(0, magDb.size - 1)
        val floorEndBin = (floorEndHz / binHz).toInt().coerceIn(floorStartBin, magDb.size - 1)
        
        val heuristicFloor = if (floorEndBin > floorStartBin) {
            val floorBins = magDb.sliceArray(floorStartBin..floorEndBin).sortedArray()
            floorBins[floorBins.size / 2]
        } else -85.0

        // Establish the definitive noise floor with a safety margin.
        // We take the higher of the two (more conservative) to avoid false cutoff detections.
        val noiseFloorDb = max(statisticalFloor, heuristicFloor) + 10.0

        val sortedMag = magDb.sortedArray()
        val quartileSize = (sortedMag.size * 0.25).toInt().coerceAtLeast(1)
        val topQuartileAvg = sortedMag.sliceArray(sortedMag.size - quartileSize until sortedMag.size).average()

        // Empirical threshold: if the loudest part of the spectrum is lower than -100dB, 
        // the signal is functionally silent.
        if (topQuartileAvg < -100) return 0

        val midEnd = (4000 / binHz).toInt().coerceIn(0, magDb.size)

        // Bandwidth-Based Signal Detection
        // Instead of hardcoded bin counts, we require a physical bandwidth of 30 Hz.
        // This ensures the detection logic is independent of the FFT size.
        val requiredConsecutiveBins = ceil(30.0 / binHz).toInt().coerceAtLeast(1)
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
