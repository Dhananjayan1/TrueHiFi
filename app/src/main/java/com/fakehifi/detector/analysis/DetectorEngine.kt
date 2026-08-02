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
        val initial = FakeDetector.classify(
            track = context.track,
            format = context.format,
            spectral = results.spectral,
            bitDepthResult = results.bitDepth,
            stereoResult = results.stereo,
            qualityResult = results.quality,
            isDeepScan = context.isDeepScan
        )

        // Automatic Escalation Logic:
        // If the result is borderline or has high spectral variance, we signal 
        // that a deep scan should be performed to resolve the ambiguity.
        val needsEscalation = !context.isDeepScan && 
            (initial.confidencePercent in 35..55 || results.spectral.consistencyPenalty > 20)

        return initial.copy(escalationRequired = needsEscalation)
    }
}
