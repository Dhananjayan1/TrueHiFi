package com.fakehifi.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackResultDao {

    @Query("SELECT * FROM track_results")
    fun observeAll(): Flow<List<TrackResultEntity>>

    @Query("SELECT * FROM track_results WHERE filePath = :filePath LIMIT 1")
    suspend fun findByPath(filePath: String): TrackResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackResultEntity)

    @Query("DELETE FROM track_results WHERE filePath = :filePath")
    suspend fun delete(filePath: String)

    @Query("DELETE FROM track_results")
    suspend fun clearAll()
}
