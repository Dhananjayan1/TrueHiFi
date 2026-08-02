package com.fakehifi.detector.analysis

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class StereoResult(
    val hasJointStereoCollapse: Boolean,
    val sideToMidHighFreqRatio: Double,
    val confidencePenalty: Int
)

object StereoAnalyzer {

    private const val FFT_SIZE = 16384
    private const val HIGH_FREQ_START_HZ = 16000

    fun analyze(stereoWindows: List<StereoWindow>?, sampleRateHz: Int): StereoResult {
        if (stereoWindows == null || stereoWindows.isEmpty() || sampleRateHz <= 0) {
            return StereoResult(false, 1.0, 0)
        }

        var totalMidHighEnergy = 0.0
        var totalSideHighEnergy = 0.0
        var totalMidFullEnergy = 0.0
        var totalSideFullEnergy = 0.0
        var validWindows = 0

        val startBinHigh = (HIGH_FREQ_START_HZ * FFT_SIZE / sampleRateHz).coerceIn(0, FFT_SIZE / 2)
        val endBin = FFT_SIZE / 2

        for (window in stereoWindows) {
            if (window.left.size < FFT_SIZE || window.right.size < FFT_SIZE) continue

            val mid = FloatArray(FFT_SIZE) { i -> (window.left[i] + window.right[i]) / 2f }
            val side = FloatArray(FFT_SIZE) { i -> (window.left[i] - window.right[i]) / 2f }

            val midMag = magnitudeSpectrum(mid)
            val sideMag = magnitudeSpectrum(side)

            // Global Ratio check: across full spectrum
            for (i in 0 until endBin) {
                totalMidFullEnergy += midMag[i]
                totalSideFullEnergy += sideMag[i]
            }

            // High Frequency Collapse check: >16kHz
            var midEnergyHigh = 0.0
            var sideEnergyHigh = 0.0
            for (i in startBinHigh until endBin) {
                midEnergyHigh += midMag[i]
                sideEnergyHigh += sideMag[i]
            }

            if (midEnergyHigh > 1e-6) {
                totalMidHighEnergy += midEnergyHigh
                totalSideHighEnergy += sideEnergyHigh
                validWindows++
            }
        }

        if (validWindows == 0) return StereoResult(false, 1.0, 0)

        val globalRatio = totalSideFullEnergy / totalMidFullEnergy
        val highFreqRatio = totalSideHighEnergy / totalMidHighEnergy
        
        // If the global Side-to-Mid ratio is extremely low (< 0.05), the track is 
        // essentially Mono or has a very narrow stereo image. In this case, we 
        // completely bypass the collapse penalty to prevent false positives.
        val isNarrowOrMono = globalRatio < 0.05
        
        val collapsed = !isNarrowOrMono && highFreqRatio < 0.08
        val penalty = if (collapsed) {
            if (highFreqRatio < 0.02) 40 else 20
        } else 0

        return StereoResult(collapsed, highFreqRatio, penalty)
    }

    private fun magnitudeSpectrum(window: FloatArray): DoubleArray {
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val hann = 0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (FFT_SIZE - 1)))
            real[i] = window[i].toDouble() * hann
        }
        FFT.transform(real, imag)
        return DoubleArray(FFT_SIZE / 2) { i -> sqrt(real[i] * real[i] + imag[i] * imag[i]) }
    }
}
