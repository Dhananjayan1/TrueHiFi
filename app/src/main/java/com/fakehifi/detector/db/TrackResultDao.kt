package com.fakehifi.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackResultDao {

    companion object {
        const val SUMMARY_COLUMNS = "filePath, uri, title, artist, sizeBytes, dateAdded, durationMs, sampleRateHz, bitDepth, detectedCutoffHz, confidencePercent, verdict, reason, bitDepthChecked, bitDepthLooksPadded, bitDepthZeroLowBytePercent, peakDb, rmsDb, medianRmsDb, dynamicRange, drRating, isBrickwalled, clippedSamplesCount, maxConsecutiveClipped, hasJointStereoCollapse, sideToMidHighFreqRatio, stereoConfidencePenalty, originalBitrateKbps, isDeepScan"
    }

    @Query("SELECT $SUMMARY_COLUMNS FROM track_results")
    fun observeAll(): Flow<List<TrackResultSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM track_results ORDER BY title ASC")
    fun observeAllByTitle(): Flow<List<TrackResultSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM track_results ORDER BY dateAdded DESC")
    fun observeAllByLatest(): Flow<List<TrackResultSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM track_results ORDER BY CASE verdict WHEN 'FAKE' THEN 0 WHEN 'SUSPICIOUS' THEN 1 WHEN 'GENUINE' THEN 2 ELSE 3 END ASC, title ASC")
    fun observeAllByVerdict(): Flow<List<TrackResultSummary>>

    @Query("SELECT count(*) FROM track_results WHERE verdict = 'FAKE'")
    fun countFakes(): Flow<Int>

    @Query("SELECT count(*) FROM track_results WHERE verdict = 'SUSPICIOUS'")
    fun countSuspicious(): Flow<Int>

    @Query("SELECT * FROM track_results WHERE uri = :uri LIMIT 1")
    fun observeByUri(uri: String): Flow<TrackResultEntity?>

    @Query("SELECT * FROM track_results")
    suspend fun getAll(): List<TrackResultEntity>

    @Query("SELECT * FROM track_results WHERE filePath = :filePath LIMIT 1")
    suspend fun findByPath(filePath: String): TrackResultEntity?

    @Query("SELECT * FROM track_results WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): TrackResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackResultEntity)

    @Query("DELETE FROM track_results WHERE filePath = :filePath")
    suspend fun delete(filePath: String)

    @Query("DELETE FROM track_results")
    suspend fun clearAll()
}
