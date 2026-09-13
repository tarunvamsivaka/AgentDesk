package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {

    @Query("SELECT * FROM command_record ORDER BY issued_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CommandRecordEntity>>

    @Query("SELECT * FROM command_record WHERE thread_id = :threadId ORDER BY issued_at ASC")
    suspend fun findByThread(threadId: Long): List<CommandRecordEntity>

    @Insert
    suspend fun insert(record: CommandRecordEntity): Long

    @Query(
        "UPDATE command_record SET intent_name = :intentName, " +
        "status = :status, latency_ms = :latencyMs WHERE id = :id"
    )
    suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long)

    @Query("DELETE FROM command_record WHERE issued_at < :beforeEpoch")
    suspend fun deleteOlderThan(beforeEpoch: Long)
}
