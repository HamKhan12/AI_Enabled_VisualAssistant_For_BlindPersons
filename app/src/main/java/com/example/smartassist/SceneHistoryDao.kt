package com.example.smartassist

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SceneHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scene: SceneHistory)

    @Query("SELECT * FROM scene_history ORDER BY timestamp DESC")
    fun getAllScenes(): Flow<List<SceneHistory>>

    @Query("SELECT * FROM scene_history WHERE language = :lang ORDER BY timestamp DESC LIMIT 100")
    fun getScenesByLanguage(lang: String): Flow<List<SceneHistory>>

    @Query("SELECT * FROM scene_history WHERE imageHash = :hash")
    suspend fun findByHash(hash: String): SceneHistory?

    @Query("SELECT * FROM scene_history ORDER BY timestamp DESC")
    suspend fun getAllScenesList(): List<SceneHistory>

    @Query("SELECT * FROM scene_history WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getRecentScenes(since: Long): List<SceneHistory>

    @Query("DELETE FROM scene_history WHERE id NOT IN (SELECT id FROM scene_history ORDER BY timestamp DESC LIMIT 500)")
    suspend fun deleteOldScenes()

    @Query("DELETE FROM scene_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scene_history")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM scene_history WHERE wasDanger = 1")
    suspend fun getDangerCount(): Int
}