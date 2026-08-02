package com.fakehifi.detector.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fakehifi.detector.db.AppDatabase
import com.fakehifi.detector.model.ResultFilter
import com.fakehifi.detector.model.ScanUiState
import com.fakehifi.detector.model.SortOrder
import com.fakehifi.detector.model.Verdict
import com.fakehifi.detector.repository.ScanRepository
import com.fakehifi.detector.worker.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    // Apply sampling to the UI state to prevent Main thread bombardment during batch scans.
    // The background ScanWorker continues to update the repository at full speed.
    val uiState: StateFlow<ScanUiState> = ScanRepository.uiState
        .sample(500L)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ScanRepository.uiState.value
        )

    private val db = AppDatabase.get(application)

    init {
        // Repopulate from the on-disk cache on first launch/process restart,
        // so previous scan results aren't lost until the user rescans.
        viewModelScope.launch {
            if (ScanRepository.uiState.value.results.isEmpty()) {
                val cached = withContext(Dispatchers.IO) { db.trackResultDao().observeAll().first() }
                if (cached.isNotEmpty()) {
                    ScanRepository.update { it.copy(results = cached.map { entity -> entity.toTrackResult() }) }
                }
            }
        }
    }

    fun startScan() {
        val workRequest = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(Data.Builder().putString(ScanWorker.KEY_ACTION, ScanWorker.ACTION_START_SCAN).build())
            .build()
        
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("full_scan", ExistingWorkPolicy.REPLACE, workRequest)
    }

    fun startDeepScan(trackUri: String) {
        val workRequest = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ScanWorker.KEY_ACTION, ScanWorker.ACTION_DEEP_SCAN)
                    .putString(ScanWorker.KEY_URI, trackUri)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("deep_scan_${trackUri.hashCode()}", ExistingWorkPolicy.REPLACE, workRequest)
    }

    fun cancelScan() {
        WorkManager.getInstance(getApplication()).cancelUniqueWork("full_scan")
    }

    fun setFilter(filter: ResultFilter) {
        ScanRepository.update { it.copy(filter = filter) }
    }

    fun setSortOrder(order: SortOrder) {
        ScanRepository.update { it.copy(sortOrder = order) }
    }

    fun setSearchQuery(query: String) {
        ScanRepository.update { it.copy(searchQuery = query) }
    }

    fun clearCacheAndResults() {
        viewModelScope.launch(Dispatchers.IO) {
            db.trackResultDao().clearAll()
            ScanRepository.update { it.copy(results = emptyList()) }
        }
    }

    fun toggleSelection(uri: String) {
        ScanRepository.update { state ->
            val newSelected = if (state.selectedUris.contains(uri)) {
                state.selectedUris - uri
            } else {
                state.selectedUris + uri
            }
            state.copy(
                selectedUris = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        ScanRepository.update { it.copy(selectedUris = emptySet(), isSelectionMode = false) }
    }

    fun createDeleteRequest(uris: List<String>): PendingIntent? {
        val contentResolver = getApplication<Application>().contentResolver
        // 1. Before generating the request, ensure the target URIs actually still exist on the disk.
        val validUris = uris.map { it.toUri() }.filter { uri ->
            try {
                contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { cursor ->
                    cursor.moveToFirst()
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
        
        if (validUris.isEmpty()) return null
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(contentResolver, validUris)
        } else {
            null
        }
    }

    fun createDeleteFakeRequest(): PendingIntent? {
        val uris = uiState.value.results
            .filter { it.verdict == Verdict.FAKE }
            .map { it.track.uri }
        return createDeleteRequest(uris)
    }

    fun createDeleteSuspiciousRequest(): PendingIntent? {
        val uris = uiState.value.results
            .filter { it.verdict == Verdict.SUSPICIOUS }
            .map { it.track.uri }
        return createDeleteRequest(uris)
    }

    fun onTracksDeleted(deletedUris: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val results = uiState.value.results
            val currentResults = results.toMutableList()
            val iterator = currentResults.iterator()
            val removedUris = mutableSetOf<String>()

            while (iterator.hasNext()) {
                val res = iterator.next()
                if (deletedUris.contains(res.track.uri)) {
                    // 2. Re-query the ContentResolver for the requested URIs. 
                    // Only remove from local DB if confirmed they no longer exist.
                    val uri = res.track.uri.toUri()
                    val stillExists = try {
                        contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { cursor ->
                            cursor.moveToFirst()
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }

                    if (!stillExists) {
                        db.trackResultDao().delete(res.track.filePath)
                        iterator.remove()
                        removedUris.add(res.track.uri)
                    }
                }
            }
            
            ScanRepository.update { 
                it.copy(
                    results = currentResults,
                    selectedUris = it.selectedUris - removedUris,
                    isSelectionMode = (it.selectedUris - removedUris).isNotEmpty()
                )
            }
        }
    }

    // Legacy method for compatibility if needed, but we should use the one above
    fun onTracksDeleted() {
        val fakeUris = uiState.value.results
            .filter { it.verdict == Verdict.FAKE }
            .map { it.track.uri }
        onTracksDeleted(fakeUris)
    }
}
