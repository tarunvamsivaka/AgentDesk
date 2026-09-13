package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfirmationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(confirmation: ConfirmationRecordEntity): Long

    @Query("SELECT * FROM confirmation_record WHERE task_id = :taskId ORDER BY prompted_at DESC LIMIT 1")
    suspend fun getForTask(taskId: Long): ConfirmationRecordEntity?

    @Query("SELECT * FROM confirmation_record ORDER BY prompted_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ConfirmationRecordEntity>>

    @Query("DELETE FROM confirmation_record WHERE prompted_at < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
