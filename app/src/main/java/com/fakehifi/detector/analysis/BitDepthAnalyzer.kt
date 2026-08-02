package com.fakehifi.detector.analysis

import com.fakehifi.detector.model.BitDepthResult

object BitDepthAnalyzer {

    /**
     * Looks at the lowest byte of an integer representation of each sample.
     * Genuine 24-bit audio has real (if quiet) information in the lowest bits;
     * a file that's actually 16-bit content zero-padded up to 24-bit has that
     * area sitting at exactly zero.
     *
     * NOTE: This check is only performed on un-normalized integer PCM to
     * avoid artifacts from float conversion.
     */
    fun analyze(integerWindows: List<IntArray>?, claimedBitDepth: Int): BitDepthResult {
        // If we don't have integer samples, or the file doesn't claim to be 
        // high-resolution (>16-bit), the LSB padding check is not applicable.
        if (integerWindows == null || integerWindows.isEmpty() || claimedBitDepth <= 16) {
            return BitDepthResult(checked = false, looksPadded = false, zeroLowBytePercent = 0)
        }

        var zeroCount = 0L
        var totalCount = 0L

        for (window in integerWindows) {
            for (sample in window) {
                // We check the lowest byte (LSB). 
                // Note: For this to be scientifically valid, the integer samples 
                // must represent the full bit-depth of the source (e.g. 24-bit 
                // samples). If the decoder truncated to 16-bit, this byte 
                // will contain 16-bit dither/noise instead of 24-bit padding.
                val lowByte = sample and 0xFF
                if (lowByte == 0) zeroCount++
                totalCount++
            }
        }

        if (totalCount == 0L) {
            return BitDepthResult(checked = false, looksPadded = false, zeroLowBytePercent = 0)
        }

        val zeroPercent = ((zeroCount * 100) / totalCount).toInt()
        
        // For genuine high-bit-depth audio, the LSB is almost never 
        // consistently zero due to dither or noise floor.
        return BitDepthResult(
            checked = true, 
            looksPadded = zeroPercent > 90, 
            zeroLowBytePercent = zeroPercent
        )
    }
}
