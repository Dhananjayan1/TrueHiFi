package com.fakehifi.detector.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.fakehifi.detector.analysis.AudioContext
import com.fakehifi.detector.analysis.AudioDecoder
import com.fakehifi.detector.analysis.DecodedFormat
import com.fakehifi.detector.analysis.DetectorEngine
import com.fakehifi.detector.analysis.FakeDetector
import com.fakehifi.detector.analysis.SpectralResult
import com.fakehifi.detector.db.AppDatabase
import com.fakehifi.detector.db.TrackResultEntity
import com.fakehifi.detector.model.TrackInfo
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.repository.ScanRepository
import com.fakehifi.detector.scanner.MusicScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = AppDatabase.get(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val detectorEngine = DetectorEngine()

    override suspend fun doWork(): WorkResult {
        val action = inputData.getString(KEY_ACTION)
        val uriString = inputData.getString(KEY_URI)

        createNotificationChannel()
        
        return try {
            when (action) {
                ACTION_START_SCAN -> startFullScan()
                ACTION_DEEP_SCAN -> uriString?.let { startDeepScan(it) } ?: return WorkResult.failure()
                else -> return WorkResult.failure()
            }
            WorkResult.success()
        } catch (e: Exception) {
            ScanRepository.update { it.copy(isScanning = false) }
            WorkResult.failure()
        }
    }

    private suspend fun startFullScan() {
        updateForeground("Starting scan…", 0, 0)
        
        val tracks = withContext(Dispatchers.IO) { MusicScanner.findAllTracks(applicationContext) }
        ScanRepository.update {
            it.copy(isScanning = true, totalTracks = tracks.size, scannedTracks = 0, results = emptyList())
        }

        var lastUpdateMs = 0L
        for ((index, track) in tracks.withIndex()) {
            kotlinx.coroutines.yield()
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUpdateMs > 100) {
                ScanRepository.update { it.copy(currentTitle = track.title, scannedTracks = index + 1) }
                updateForeground("${index + 1}/${tracks.size} — ${track.title}", index + 1, tracks.size)
                lastUpdateMs = currentTime
            }

            val cached = withContext(Dispatchers.IO) { db.trackResultDao().findByPath(track.filePath) }
            try {
                if (cached != null &&
                    cached.sizeBytes == track.sizeBytes &&
                    cached.dateAdded == track.dateAdded
                ) {
                    // Already cached
                } else {
                    var result = analyzeTrack(track, deep = false)
                    
                    if (result.escalationRequired) {
                        // Automatically upgrade to deep scan if result is ambiguous
                        result = analyzeTrack(track, deep = true)
                    }

                    withContext(Dispatchers.IO) {
                        db.trackResultDao().upsert(TrackResultEntity.fromTrackResult(result))
                    }
                }
            } catch (e: Exception) {
                // Skip problematic track and continue scan
                e.printStackTrace()
            }
            
            // Ensure the repository is updated with the final count if we skipped the throttle
            if (index == tracks.size - 1) {
                ScanRepository.update { it.copy(scannedTracks = tracks.size) }
            }
            // on every track. The UI observes the database flow for the list.
            // We only update the progress markers.
        }

        ScanRepository.update { it.copy(isScanning = false, currentTitle = "", scannedTracks = tracks.size) }
    }

    private suspend fun startDeepScan(uriString: String) {
        val existing = withContext(Dispatchers.IO) {
            db.trackResultDao().findByUri(uriString)?.toTrackResult()
        } ?: return
        
        updateForeground("Deep scanning ${existing.track.title}…", 0, 1)
        ScanRepository.update { it.copy(isScanning = true, currentTitle = existing.track.title) }
        
        val fresh = analyzeTrack(existing.track, deep = true)
        withContext(Dispatchers.IO) {
            db.trackResultDao().upsert(TrackResultEntity.fromTrackResult(fresh))
        }

        ScanRepository.update { state ->
            state.copy(
                isScanning = false,
                currentTitle = "",
                results = state.results.map { if (it.track.uri == uriString) fresh else it }
            )
        }
    }

    private suspend fun analyzeTrack(track: TrackInfo, deep: Boolean): TrackResult = withContext(Dispatchers.Default) {
        val windowCount = if (deep) AudioDecoder.DEEP_SCAN_WINDOW_COUNT else AudioDecoder.QUICK_SCAN_WINDOW_COUNT
        val windowMs = if (deep) AudioDecoder.DEEP_SCAN_WINDOW_MS else AudioDecoder.QUICK_SCAN_WINDOW_MS

        val decoded = AudioDecoder.decodeSampleWindows(applicationContext, Uri.parse(track.uri), windowCount, windowMs)
            ?: return@withContext FakeDetector.classify(
                track = track,
                format = DecodedFormat(0, 0, 0),
                spectral = SpectralResult(0, 0, 0.0, 0.0, emptyList()),
                bitDepthResult = null,
                stereoResult = null,
                isDeepScan = deep
            )

        val audioContext = AudioContext(
            track = track,
            format = decoded.format,
            windows = decoded.windows,
            integerWindows = decoded.integerWindows,
            stereoWindows = decoded.stereoWindows,
            isDeepScan = deep
        )

        detectorEngine.runAnalysis(audioContext)
    }

    private suspend fun updateForeground(text: String, progress: Int, max: Int) {
        val notification = buildNotification(text, progress, max)
        val foregroundInfo = if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
        setForeground(foregroundInfo)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Library scan", NotificationManager.IMPORTANCE_LOW)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Scanning music library")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setProgress(max.coerceAtLeast(1), progress, max == 0)
            .build()
    }

    companion object {
        const val KEY_ACTION = "action"
        const val KEY_URI = "uri"
        const val ACTION_START_SCAN = "start_scan"
        const val ACTION_DEEP_SCAN = "deep_scan"
        
        private const val CHANNEL_ID = "scan_channel"
        private const val NOTIF_ID = 42
    }
}
