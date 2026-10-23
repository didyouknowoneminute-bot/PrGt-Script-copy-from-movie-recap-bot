package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrGtScriptDao {
    @Query("SELECT * FROM prgt_scripts ORDER BY timestamp DESC")
    fun getAllScripts(): Flow<List<PrGtScript>>

    @Insert
    suspend fun insertScript(script: PrGtScript): Long

    @Update
    suspend fun updateScript(script: PrGtScript)

    @Query("DELETE FROM prgt_scripts WHERE id = :id")
    suspend fun deleteScriptById(id: Int)

    @Query("UPDATE prgt_scripts SET fileName = :newName WHERE id = :id")
    suspend fun updateFileName(id: Int, newName: String)

    @Query("UPDATE prgt_scripts SET scriptText = :newText WHERE id = :id")
    suspend fun updateScriptText(id: Int, newText: String)

    @Query("DELETE FROM prgt_scripts WHERE timestamp < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    @Query("DELETE FROM prgt_scripts WHERE id NOT IN (SELECT id FROM prgt_scripts ORDER BY timestamp DESC LIMIT 50)")
    suspend fun keepLatestFifty()

    @Query("DELETE FROM prgt_scripts")
    suspend fun deleteAllScripts()
}
