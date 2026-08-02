package com.fakehifi.detector.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal in-place radix-2 Cooley-Tukey FFT.
 * `real` and `imag` must have the same power-of-two length.
 */
object FFT {

    fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
        if (n <= 1) return

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wReal = cos(ang)
            val wImag = sin(ang)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val vImag = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal

                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag

                    val nextReal = curReal * wReal - curImag * wImag
                    val nextImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                    curImag = nextImag
                }
                i += len
            }
            len = len shl 1
        }
    }
}
