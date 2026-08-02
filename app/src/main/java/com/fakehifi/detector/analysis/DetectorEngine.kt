package com.fakehifi.detector.analysis

import com.fakehifi.detector.model.BitDepthResult
import com.fakehifi.detector.model.TrackResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Aggregated results from all analysis components.
 */
data class AnalysisResults(
    val spectral: SpectralResult,
    val stereo: StereoResult,
    val bitDepth: BitDepthResult,
    val quality: QualityResult
)

class DetectorEngine(
    private val components: List<AudioAnalyzerComponent> = listOf(
        SpectralAnalyzer,
        StereoAnalyzer,
        BitDepthAnalyzer,
        QualityAnalyzer
    )
) {
    suspend fun runAnalysis(audioContext: AudioContext): TrackResult = coroutineScope {
        val resultsMap = mutableMapOf<Class<out AudioAnalyzerComponent>, ComponentResult>()
        
        // Execute components concurrently
        val deferredResults = components.map { component ->
            async { component to component.analyze(audioContext) }
        }
        
        deferredResults.forEach { deferred ->
            val (component, result) = deferred.await()
            resultsMap[component.javaClass] = result
        }

        val aggregated = AnalysisResults(
            spectral = resultsMap[SpectralAnalyzer.javaClass] as SpectralResult,
            stereo = resultsMap[StereoAnalyzer.javaClass] as StereoResult,
            bitDepth = resultsMap[BitDepthAnalyzer.javaClass] as BitDepthResult,
            quality = resultsMap[QualityAnalyzer.javaClass] as QualityResult
        )

        VerdictGenerator.generate(audioContext, aggregated)
    }
}

object VerdictGenerator {
    fun generate(context: AudioContext, results: AnalysisResults): TrackResult {
        // This will leverage the logic currently in FakeDetector.kt
        return FakeDetector.classify(
            track = context.track,
            format = context.format,
            spectral = results.spectral,
            bitDepthResult = results.bitDepth,
            stereoResult = results.stereo,
            qualityResult = results.quality,
            isDeepScan = context.isDeepScan
        )
    }
}
