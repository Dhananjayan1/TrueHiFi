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
import com.fakehifi.detector.db.TrackResultSummary
import com.fakehifi.detector.model.ResultFilter
import com.fakehifi.detector.model.ScanUiState
import com.fakehifi.detector.model.SortOrder
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.model.Verdict
import com.fakehifi.detector.repository.ScanRepository
import com.fakehifi.detector.worker.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val _sortOrder = MutableStateFlow(SortOrder.LATEST_FIRST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val resultFlowCache = mutableMapOf<String, StateFlow<TrackResult?>>()

    private val mappedResultsFlow = ScanRepository.uiState.map { it.isScanning }.distinctUntilChanged()
        .flatMapLatest { isScanning ->
            _sortOrder.flatMapLatest { order ->
                val baseFlow = when (order) {
                    SortOrder.TITLE_A_TO_Z -> db.trackResultDao().observeAllByTitle()
                    SortOrder.LATEST_FIRST -> db.trackResultDao().observeAllByLatest()
                    SortOrder.VERDICT -> db.trackResultDao().observeAllByVerdict()
                }
                // Throttle updates during scan to keep UI fluid, 
                // but ensure we don't block the very first emission.
                if (isScanning) baseFlow.sample(200) else baseFlow
            }
        }
        .map { summaries ->
            withContext(Dispatchers.Default) {
                summaries.map { it.toTrackResult() }
            }
        }.distinctUntilChanged()

    private val filterCriteriaFlow = ScanRepository.uiState.map { 
        it.filter to it.searchQuery 
    }.distinctUntilChanged()

    private val filteredResultsFlow = combine(mappedResultsFlow, filterCriteriaFlow) { mapped, criteria ->
        val (filter, searchQuery) = criteria
        withContext(Dispatchers.Default) {
            val filtered = when (filter) {
                ResultFilter.ALL -> mapped
                ResultFilter.FAKE -> mapped.filter { it.verdict == Verdict.FAKE }
                ResultFilter.SUSPICIOUS -> mapped.filter { it.verdict == Verdict.SUSPICIOUS }
                ResultFilter.GENUINE -> mapped.filter { it.verdict == Verdict.GENUINE }
                ResultFilter.UNKNOWN -> mapped.filter { it.verdict == Verdict.UNKNOWN }
            }

            val finalResults = if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                filtered.filter { 
                    it.track.title.lowercase().contains(q) || 
                    it.track.artist.lowercase().contains(q) ||
                    it.track.filePath.lowercase().contains(q)
                }
            } else {
                filtered
            }
            
            val fakes = mapped.count { it.verdict == Verdict.FAKE }
            val suspicious = mapped.count { it.verdict == Verdict.SUSPICIOUS }
            
            ScanUiState(
                results = mapped,
                filteredResults = finalResults,
                fakeCount = fakes,
                suspiciousCount = suspicious
            )
        }
    }.distinctUntilChanged()

    // Combine scanning status from repository with sorted results from database
    val uiState: StateFlow<ScanUiState> = combine(
        ScanRepository.uiState,
        filteredResultsFlow,
        _sortOrder
    ) { repoState, computedState, order ->
        repoState.copy(
            results = computedState.results,
            filteredResults = computedState.filteredResults,
            fakeCount = computedState.fakeCount,
            suspiciousCount = computedState.suspiciousCount,
            sortOrder = order
        )
    }
    .conflate() // Ensure we don't build up a backlog of states
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScanRepository.uiState.value
    )

    init {
        // Initial setup if needed, but results are now driven by the combined Flow
    }

    fun startScan(folderUri: String? = null) {
        val workRequest = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ScanWorker.KEY_ACTION, ScanWorker.ACTION_START_SCAN)
                    .putString(ScanWorker.KEY_FOLDER_URI, folderUri)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("full_scan", ExistingWorkPolicy.REPLACE, workRequest)
    }

    fun startSingleFileScan(uri: android.net.Uri) {
        val workRequest = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ScanWorker.KEY_ACTION, ScanWorker.ACTION_SINGLE_FILE_SCAN)
                    .putString(ScanWorker.KEY_URI, uri.toString())
                    .build()
            )
            .build()
        
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("single_scan_${uri.hashCode()}", ExistingWorkPolicy.REPLACE, workRequest)
    }

    fun startDeepScan(trackUri: String) {
        println("TrueHiFi: ViewModel requesting deep scan for $trackUri")
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
        _sortOrder.value = order
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

    fun selectAllByVerdict(verdict: Verdict) {
        val uris = uiState.value.results.filter { it.verdict == verdict }.map { it.track.uri }.toSet()
        ScanRepository.update { it.copy(selectedUris = uris, isSelectionMode = uris.isNotEmpty()) }
    }

    fun selectAllFiltered() {
        val uris = uiState.value.filteredResults.map { it.track.uri }.toSet()
        ScanRepository.update { it.copy(selectedUris = uris, isSelectionMode = uris.isNotEmpty()) }
    }

    fun observeFullResult(uri: String): StateFlow<TrackResult?> = synchronized(resultFlowCache) {
        resultFlowCache.getOrPut(uri) {
            db.trackResultDao().observeByUri(uri)
                .map { it?.toTrackResult() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = null
                )
        }
    }

    suspend fun getFullResult(uri: String): TrackResult? = withContext(Dispatchers.IO) {
        db.trackResultDao().findByUri(uri)?.toTrackResult()
    }

    fun createDeleteRequest(uris: List<String>): PendingIntent? {
        val contentResolver = getApplication<Application>().contentResolver
        val validUris = filterValidUris(uris)
        if (validUris.isEmpty()) return null
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(contentResolver, validUris)
        } else {
            null
        }
    }

    fun createTrashRequest(uris: List<String>, trash: Boolean): PendingIntent? {
        val contentResolver = getApplication<Application>().contentResolver
        val validUris = filterValidUris(uris)
        if (validUris.isEmpty()) return null
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createTrashRequest(contentResolver, validUris, trash)
        } else {
            null
        }
    }

    private fun filterValidUris(uris: List<String>): List<android.net.Uri> {
        val contentResolver = getApplication<Application>().contentResolver
        return uris.map { it.toUri() }.filter { uri ->
            try {
                contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { cursor ->
                    cursor.moveToFirst()
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
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
}
