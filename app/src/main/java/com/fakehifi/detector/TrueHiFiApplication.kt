package com.fakehifi.detector

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fakehifi.detector.worker.ScanWorker

class TrueHiFiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Register ContentObserver to listen for new music files
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                triggerIncrementalScan()
            }
        }
        
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
    }

    private fun triggerIncrementalScan() {
        val workRequest = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ScanWorker.KEY_ACTION, ScanWorker.ACTION_INCREMENTAL_SCAN)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(this)
            .enqueueUniqueWork("incremental_scan", ExistingWorkPolicy.KEEP, workRequest)
    }
}
