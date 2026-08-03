package com.fakehifi.detector.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = AppDatabase.get(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val detectorEngine = DetectorEngine()

    override suspend fun doWork(): WorkResult {
        val action = inputData.getString(KEY_ACTION)
        val uriString = inputData.getString(KEY_URI)
        val folderUri = inputData.getString(KEY_FOLDER_URI)

        createNotificationChannel()
        
        return try {
            println("TrueHiFi: Worker received action: $action for uri: $uriString")
            when (action) {
                ACTION_START_SCAN -> startFullScan(folderUri)
                ACTION_DEEP_SCAN -> uriString?.let { startDeepScan(it) } ?: return WorkResult.failure()
                ACTION_SINGLE_FILE_SCAN -> uriString?.let { startSingleFileScan(it) } ?: return WorkResult.failure()
                ACTION_INCREMENTAL_SCAN -> startIncrementalScan()
                else -> return WorkResult.failure()
            }
            WorkResult.success()
        } catch (e: Throwable) {
            ScanRepository.update { it.copy(isScanning = false) }
            WorkResult.failure()
        }
    }

    private suspend fun startFullScan(folderUri: String?) {
        updateForeground("Starting scan…", 0, 0)
        
        val tracks = withContext(Dispatchers.IO) { MusicScanner.findAllTracks(applicationContext, folderUri) }
        ScanRepository.update {
            it.copy(isScanning = true, totalTracks = tracks.size, scannedTracks = 0, results = emptyList())
        }

        val semaphore = Semaphore(4)
        var scannedCount = 0
        var lastUpdateMs = 0L

        coroutineScope {
            for (track in tracks) {
                launch {
                    semaphore.withPermit {
                        val cached = withContext(Dispatchers.IO) { db.trackResultDao().findByPath(track.filePath) }
                        try {
                            if (cached != null &&
                                cached.sizeBytes == track.sizeBytes &&
                                cached.dateAdded == track.dateAdded
                            ) {
                                // Already cached
                            } else {
                                val result = withTimeoutOrNull(30_000) {
                                    var r = analyzeTrack(track, deep = false)
                                    if (r.escalationRequired) {
                                        r = analyzeTrack(track, deep = true)
                                    }
                                    r
                                }

                                if (result != null) {
                                    withContext(Dispatchers.IO) {
                                        db.trackResultDao().upsert(TrackResultEntity.fromTrackResult(result))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("TrueHiFi: Error scanning ${track.title}: ${e.message}")
                        } finally {
                            synchronized(this@ScanWorker) {
                                scannedCount++
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastUpdateMs > 500 || scannedCount == tracks.size) {
                                    lastUpdateMs = currentTime
                                    val count = scannedCount
                                    ScanRepository.update { it.copy(currentTitle = track.title, scannedTracks = count) }
                                    launch { updateForeground("${count}/${tracks.size} — ${track.title}", count, tracks.size) }
                                }
                            }
                        }
                    }
                }
            }
        }

        ScanRepository.update { it.copy(isScanning = false, currentTitle = "", scannedTracks = tracks.size) }
    }

    private suspend fun startSingleFileScan(uriString: String) {
        val track = withContext(Dispatchers.IO) { MusicScanner.getTrackInfoFromUri(applicationContext, Uri.parse(uriString)) }
            ?: return
        
        ScanRepository.update { it.copy(isScanning = true, currentTitle = track.title) }
        
        // Take persistable permission if needed
        try {
            applicationContext.contentResolver.takePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {}

        val result = analyzeTrack(track, deep = false)
        withContext(Dispatchers.IO) {
            db.trackResultDao().upsert(TrackResultEntity.fromTrackResult(result))
        }
        ScanRepository.update { it.copy(isScanning = false, currentTitle = "") }
    }

    private suspend fun startIncrementalScan() {
        // Just a quick scan of the 20 most recent tracks in MediaStore
        val recentTracks = withContext(Dispatchers.IO) { MusicScanner.findAllTracks(applicationContext) }
            .sortedByDescending { it.dateAdded }
            .take(20)

        for (track in recentTracks) {
            val cached = withContext(Dispatchers.IO) { db.trackResultDao().findByPath(track.filePath) }
            if (cached == null) {
                val result = analyzeTrack(track, deep = false)
                withContext(Dispatchers.IO) {
                    db.trackResultDao().upsert(TrackResultEntity.fromTrackResult(result))
                }
            }
        }
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
        const val KEY_FOLDER_URI = "folder_uri"
        const val ACTION_START_SCAN = "start_scan"
        const val ACTION_DEEP_SCAN = "deep_scan"
        const val ACTION_SINGLE_FILE_SCAN = "single_file_scan"
        const val ACTION_INCREMENTAL_SCAN = "incremental_scan"
        
        private const val CHANNEL_ID = "scan_channel"
        private const val NOTIF_ID = 42
    }
}
