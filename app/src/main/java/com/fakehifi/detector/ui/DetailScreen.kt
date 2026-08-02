package com.fakehifi.detector.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.model.Verdict
import com.fakehifi.detector.ui.theme.VerdictFake
import com.fakehifi.detector.ui.theme.VerdictGenuine
import com.fakehifi.detector.ui.theme.VerdictSuspicious
import com.fakehifi.detector.ui.theme.VerdictUnknown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    result: TrackResult,
    isScanning: Boolean,
    onBack: () -> Unit,
    onDeepScan: () -> Unit,
    onDelete: () -> Unit
) {
    val verdictColor = colorFor(result.verdict)
    var showSpectrogram by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(result.track.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete Track")
                    }
                }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Text(result.track.artist, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(verdictColor.copy(alpha = 0.15f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(result.verdict.name, style = MaterialTheme.typography.titleLarge, color = verdictColor)
                    Text("${result.confidencePercent}% confidence", style = MaterialTheme.typography.bodySmall)
                }
                if (result.isDeepScan) {
                    AssistChip(onClick = {}, label = { Text("Deep scan") })
                }
            }

            Spacer(Modifier.height(16.dp))

            InfoRow("Sample rate", "${result.sampleRateHz / 1000.0} kHz")
            InfoRow("Bit depth", "${result.bitDepth}-bit")
            InfoRow("Detected cutoff", "${result.detectedCutoffHz / 1000.0} kHz")
            if (result.originalBitrateKbps > 0) {
                InfoRow("Estimated source", "~${result.originalBitrateKbps} kbps")
            }
            InfoRow("Theoretical ceiling", "${result.sampleRateHz / 2000} kHz")

            result.bitDepthResult?.let { bd ->
                if (bd.checked) {
                    InfoRow(
                        "Bit-depth check",
                        if (bd.looksPadded) "Looks padded (${bd.zeroLowBytePercent}% zero)"
                        else "Has real low-bit content (${bd.zeroLowBytePercent}% zero)"
                    )
                }
            }

            result.qualityResult?.let { qr ->
                Spacer(Modifier.height(12.dp))
                InfoRow("Dynamic Range", "DR%.0f".format(qr.dynamicRange))
                InfoRow("True Peak", "%.2f dBFS".format(qr.peakDb))
                if (qr.clippedSamplesCount > 0) {
                    InfoRow("Clipping", "${qr.clippedSamplesCount} samples (max ${qr.maxConsecutiveClipped} consecutive)")
                }
            }

            result.stereoResult?.let { sr ->
                if (sr.hasJointStereoCollapse) {
                    Spacer(Modifier.height(12.dp))
                    InfoRow("Stereo integrity", "Joint Stereo Collapse detected")
                    InfoRow("Side-to-Mid ratio", "%.1f%%".format(sr.sideToMidHighFreqRatio * 100))
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showSpectrogram) "Spectrogram (Time/Freq)" else "Frequency spectrum",
                    style = MaterialTheme.typography.titleSmall
                )
                
                IconButton(onClick = { showSpectrogram = !showSpectrogram }) {
                    Icon(
                        imageVector = if (showSpectrogram) Icons.Default.AutoGraph else Icons.Default.ViewModule,
                        contentDescription = "Toggle View",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            if (showSpectrogram) {
                SpectrogramView(result = result)
            } else {
                SpectrumChart(result = result)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 kHz", style = MaterialTheme.typography.labelSmall)
                    Text("${result.sampleRateHz / 2000} kHz", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Transparency Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            ConfidenceBreakdownCard(result = result)
            
            Spacer(Modifier.height(16.dp))
            HumanReadableReasons(result = result)

            if (result.metadataMismatch.hasMismatch) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Metadata Mismatch", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text(result.metadataMismatch.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onDeepScan,
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (result.isDeepScan) "Deep scan again" else "Run deep scan (more accurate, slower)")
            }
        }
    }
}

@Composable
fun ConfidenceBreakdownCard(result: TrackResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Confidence Breakdown", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            
            result.confidenceBreakdown.forEach { contribution ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(contribution.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(contribution.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = if (contribution.scoreChange >= 0) "+${contribution.scoreChange}" else "${contribution.scoreChange}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (contribution.isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                LinearProgressIndicator(
                    progress = { (contribution.scoreChange.toFloat().coerceAtLeast(0f) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = if (contribution.isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun HumanReadableReasons(result: TrackResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Detailed Insights", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        
        result.confidenceBreakdown.forEach { contribution ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (contribution.isPositive) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                    tint = if (contribution.isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = contribution.message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        // Add more dynamic reasons based on thresholds if needed
        if (result.originalBitrateKbps > 0) {
            ReasonItem(
                icon = Icons.Default.Info,
                text = "Spectral footprint aligns with ~${result.originalBitrateKbps} kbps lossy encoding profiles.",
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun ReasonItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
            tint = color
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpectrogramView(result: TrackResult, modifier: Modifier = Modifier) {
    if (result.multiSpectrums.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("No spectrogram data", textAlign = TextAlign.Center)
        }
        return
    }

    val spectrogramBitmap = remember(result.multiSpectrums) {
        generateSpectrogramBitmap(result.multiSpectrums)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .horizontalScroll(rememberScrollState())
        ) {
            spectrogramBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Spectrogram",
                    modifier = Modifier.fillMaxHeight().width(maxOf(400.dp, (result.multiSpectrums.size * 20).dp)),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Time →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${result.sampleRateHz / 2000} kHz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun generateSpectrogramBitmap(multiSpectrums: List<List<Double>>): Bitmap? {
    if (multiSpectrums.isEmpty()) return null
    
    val timeSteps = multiSpectrums.size
    val freqBins = multiSpectrums[0].size
    if (freqBins == 0) return null

    val bitmap = Bitmap.createBitmap(timeSteps, freqBins, Bitmap.Config.ARGB_8888)
    
    for (t in 0 until timeSteps) {
        val spectrum = multiSpectrums[t]
        for (f in 0 until freqBins) {
            // Frequency axis usually has 0 at the bottom. 
            // Bitmap y=0 is top, so we reverse f.
            val db = if (f < spectrum.size) spectrum[spectrum.size - 1 - f] else -100.0
            val color = mapDbToColor(db)
            bitmap.setPixel(t, f, color.toArgb())
        }
    }
    
    return bitmap
}

/**
 * Maps dBFS magnitude to a heatmap color.
 * -100dB: Black/Dark Blue
 * -50dB: Purple/Red
 * 0dB: Bright Yellow/White
 */
private fun mapDbToColor(db: Double): Color {
    val normalized = ((db + 100.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    
    return when {
        normalized < 0.25f -> { // Black to Blue
            val t = normalized / 0.25f
            Color(0f, 0f, t * 0.5f, 1f)
        }
        normalized < 0.5f -> { // Blue to Purple/Red
            val t = (normalized - 0.25f) / 0.25f
            Color(t * 0.8f, 0f, 0.5f + t * 0.5f, 1f)
        }
        normalized < 0.75f -> { // Red to Orange
            val t = (normalized - 0.5f) / 0.25f
            Color(0.8f + t * 0.2f, t * 0.5f, 1f - t, 1f)
        }
        else -> { // Orange to White/Yellow
            val t = (normalized - 0.75f) / 0.25f
            Color(1f, 0.5f + t * 0.5f, t, 1f)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpectrumChart(result: TrackResult, modifier: Modifier = Modifier) {
    val spectrumDb = result.spectrumDb
    val multiSpectrums = result.multiSpectrums
    
    if (spectrumDb.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("No spectrum data for this track", textAlign = TextAlign.Center)
        }
        return
    }

    // Determine Y range across ALL windows to ensure visual consistency
    var minDb = spectrumDb.min()
    var maxDb = spectrumDb.max()
    multiSpectrums.forEach { window ->
        if (window.isNotEmpty()) {
            minDb = minOf(minDb, window.min())
            maxDb = maxOf(maxDb, window.max())
        }
    }
    
    val range = (maxDb - minDb).coerceAtLeast(1.0)
    val mainLineColor = MaterialTheme.colorScheme.primary
    val overlayColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val w = size.width
        val h = size.height
        
        // 1. Draw individual window overlays
        multiSpectrums.forEach { window ->
            if (window.size > 1) {
                val stepX = w / (window.size - 1)
                val path = Path()
                window.forEachIndexed { i, db ->
                    val x = i * stepX
                    val normalized = ((db - minDb) / range).toFloat().coerceIn(0f, 1f)
                    val y = h - normalized * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = overlayColor, style = Stroke(width = 1.dp.toPx()))
            }
        }

        // 2. Draw the average spectrum (main line)
        val stepX = w / (spectrumDb.size - 1).coerceAtLeast(1)
        val mainPath = Path()
        spectrumDb.forEachIndexed { i, db ->
            val x = i * stepX
            val normalized = ((db - minDb) / range).toFloat().coerceIn(0f, 1f)
            val y = h - normalized * h
            if (i == 0) mainPath.moveTo(x, y) else mainPath.lineTo(x, y)
        }
        drawPath(mainPath, color = mainLineColor, style = Stroke(width = 2.5.dp.toPx()))
    }
}

private fun colorFor(verdict: Verdict): Color = when (verdict) {
    Verdict.GENUINE -> VerdictGenuine
    Verdict.SUSPICIOUS -> VerdictSuspicious
    Verdict.FAKE -> VerdictFake
    Verdict.UNKNOWN -> VerdictUnknown
}
