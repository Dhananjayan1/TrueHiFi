package com.fakehifi.detector.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import java.nio.ByteOrder

data class DecodedFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val declaredBitDepth: Int = 0,
    val declaredSampleRateHz: Int = 0
)

data class StereoWindow(
    val left: FloatArray,
    val right: FloatArray
)

data class DecodeResult(
    val format: DecodedFormat,
    val windows: List<FloatArray>, // mono/left channel for standard analysis
    val integerWindows: List<IntArray>?, // original un-normalized integer PCM, for LSB audit
    val stereoWindows: List<StereoWindow>? // separate channels for Mid/Side analysis
)

object AudioDecoder {

    private const val TIMEOUT_US = 10_000L
    const val QUICK_SCAN_WINDOW_COUNT = 6
    const val DEEP_SCAN_WINDOW_COUNT = 24
    const val QUICK_SCAN_WINDOW_MS = 1000L
    const val DEEP_SCAN_WINDOW_MS = 2000L

    /**
     * Decodes a handful of short windows spread across the track instead of
     * the whole file, to keep a full-library scan reasonably fast. Pass a
     * larger windowCount (see DEEP_SCAN_WINDOW_COUNT) for a slower, more
     * thorough re-check of a single file.
     */
    suspend fun decodeSampleWindows(
        context: Context,
        uri: Uri,
        windowCount: Int = QUICK_SCAN_WINDOW_COUNT,
        windowDurationMs: Long = QUICK_SCAN_WINDOW_MS
    ): DecodeResult? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            retriever.setDataSource(context, uri)

            val trackIndex = selectAudioTrack(extractor) ?: return@withContext null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            
            // Extract metadata for cross-check
            val declaredSampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: sampleRate
            } else {
                sampleRate
            }
            val declaredBitDepth = guessBitDepth(format) // Metadata doesn't always have a direct bit-depth key

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            val activeCodec = MediaCodec.createDecoderByType(mime)
            codec = activeCodec
            activeCodec.configure(format, null, null, 0)
            activeCodec.start()

            // Pre-roll: Some codecs don't provide a definitive output format until they've 
            // processed some input. We'll feed it a few buffers until we get the real format info.
            var definitiveEncoding = AudioFormat.ENCODING_PCM_16BIT
            val dummyBufferInfo = MediaCodec.BufferInfo()
            var formatFound = false
            var attempts = 0
            
            while (!formatFound && attempts < 20) {
                val inIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuffer = activeCodec.getInputBuffer(inIndex)
                    val sampleSize = inBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        activeCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        activeCodec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                
                val outIndex = activeCodec.dequeueOutputBuffer(dummyBufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    definitiveEncoding = activeCodec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    formatFound = true
                } else if (outIndex >= 0) {
                    definitiveEncoding = activeCodec.getOutputFormat(outIndex).getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    activeCodec.releaseOutputBuffer(outIndex, false)
                    formatFound = true
                }
                attempts++
            }

            // Map the physical encoding to a bit-depth number for analysis
            val achievedFloat = definitiveEncoding == AudioFormat.ENCODING_PCM_FLOAT
            val sourceBitDepth = when (definitiveEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 24 // effectively 24 or 32
                AudioFormat.ENCODING_PCM_16BIT -> 16
                AudioFormat.ENCODING_PCM_8BIT -> 8
                21, 22 -> 24 // ENCODING_PCM_24BIT_PACKED / ENCODING_PCM_32BIT (API 31+)
                else -> 16
            }

            val windows = mutableListOf<FloatArray>()
            val integerWindows = if (!achievedFloat) mutableListOf<IntArray>() else null
            val stereoWindows = if (channelCount >= 2) mutableListOf<StereoWindow>() else null
            
            val safeDurationUs = if (durationUs > 0) durationUs else 30_000_000L
            val positions = evenlySpacedPositions(safeDurationUs, windowCount, windowDurationMs * 1000)

            for (startUs in positions) {
                kotlinx.coroutines.yield()
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                activeCodec.flush()
                val result = decodeOneWindow(
                    extractor, activeCodec, channelCount, sampleRate, windowDurationMs, achievedFloat
                )
                if (result.mono.isNotEmpty()) {
                    windows.add(result.mono)
                    result.integer?.let { integerWindows?.add(it) }
                    result.stereo?.let { stereoWindows?.add(it) }
                }
            }

            if (windows.isEmpty()) return@withContext null
            DecodeResult(
                DecodedFormat(
                    sampleRateHz = sampleRate,
                    bitDepth = sourceBitDepth,
                    declaredBitDepth = declaredBitDepth,
                    declaredSampleRateHz = declaredSampleRate
                ),
                windows,
                integerWindows,
                stereoWindows
            )
        } catch (e: Exception) {
            return@withContext null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { retriever.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    data class DecodedWindow(
        val mono: FloatArray,
        val integer: IntArray?,
        val stereo: StereoWindow?
    )

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun guessBitDepth(format: MediaFormat): Int {
        // Reliable for raw WAV/PCM tracks, which expose this directly. FLAC
        // and ALAC often don't surface a pre-decode bit-depth key on
        // Android, so this falls back to 16 for those - the bit-depth
        // padding check still runs whenever float decode succeeds, just
        // isn't labeled against a known "claimed" depth in that case.
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            return when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                AudioFormat.ENCODING_PCM_FLOAT -> 24
                else -> 16
            }
        }
        
        // Try specific keys for ALAC/FLAC if available
        if (format.containsKey("bits-per-sample")) return format.getInteger("bits-per-sample")
        
        return 16
    }

    private fun evenlySpacedPositions(durationUs: Long, count: Int, windowUs: Long): List<Long> {
        if (count <= 1) return listOf(durationUs / 4)
        // Skip the first/last 5% to avoid silent lead-in/out.
        val start = (durationUs * 0.05).toLong()
        val end = ((durationUs * 0.95).toLong() - windowUs).coerceAtLeast(start + 1)
        val step = (end - start) / (count - 1).coerceAtLeast(1)
        return (0 until count).map { start + it * step }
    }

    private suspend fun decodeOneWindow(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channelCount: Int,
        sampleRate: Int,
        windowDurationMs: Long,
        isFloatPcm: Boolean
    ): DecodedWindow {
        val targetSampleCount = (sampleRate * windowDurationMs / 1000).toInt()
        val outMono = FloatArray(targetSampleCount)
        val outInt = if (!isFloatPcm) IntArray(targetSampleCount) else null
        val outLeft = if (channelCount >= 2) FloatArray(targetSampleCount) else null
        val outRight = if (channelCount >= 2) FloatArray(targetSampleCount) else null
        
        var samplesDecoded = 0
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var safetyCounter = 0

        while (!sawOutputEOS && samplesDecoded < targetSampleCount && safetyCounter < 500) {
            kotlinx.coroutines.yield()
            safetyCounter++
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outIndex)
                if (outBuffer != null && bufferInfo.size > 0) {
                    outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    outBuffer.position(bufferInfo.offset)
                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    if (isFloatPcm) {
                        val floatBuffer = outBuffer.asFloatBuffer()
                        val n = floatBuffer.remaining()
                        var i = 0
                        while (i < n && samplesDecoded < targetSampleCount) {
                            val left = floatBuffer.get(i)
                            outMono[samplesDecoded] = left
                            if (channelCount >= 2 && i + 1 < n) {
                                val right = floatBuffer.get(i + 1)
                                outLeft?.set(samplesDecoded, left)
                                outRight?.set(samplesDecoded, right)
                            }
                            i += channelCount.coerceAtLeast(1)
                            samplesDecoded++
                        }
                    } else {
                        val shortBuffer = outBuffer.asShortBuffer()
                        val n = shortBuffer.remaining()
                        var i = 0
                        while (i < n && samplesDecoded < targetSampleCount) {
                            val leftInt = shortBuffer.get(i)
                            val leftFloat = leftInt / 32768f
                            outMono[samplesDecoded] = leftFloat
                            outInt?.set(samplesDecoded, leftInt.toInt())
                            
                            if (channelCount >= 2 && i + 1 < n) {
                                val rightInt = shortBuffer.get(i + 1)
                                outLeft?.set(samplesDecoded, leftFloat)
                                outRight?.set(samplesDecoded, rightInt / 32768f)
                            }
                            i += channelCount.coerceAtLeast(1)
                            samplesDecoded++
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            }
        }
        
        val finalMono = if (samplesDecoded == targetSampleCount) outMono else outMono.copyOf(samplesDecoded)
        val finalInt = if (outInt == null) null else if (samplesDecoded == targetSampleCount) outInt else outInt.copyOf(samplesDecoded)
        
        val stereo = if (outLeft != null && outRight != null) {
            val finalLeft = if (samplesDecoded == targetSampleCount) outLeft else outLeft.copyOf(samplesDecoded)
            val finalRight = if (samplesDecoded == targetSampleCount) outRight else outRight.copyOf(samplesDecoded)
            StereoWindow(finalLeft, finalRight)
        } else null
        
        return DecodedWindow(finalMono, finalInt, stereo)
    }
}
