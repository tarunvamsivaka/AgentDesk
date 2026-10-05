package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    @Query("SELECT * FROM audit_event ORDER BY occurred_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_event WHERE event_type = :eventType ORDER BY occurred_at DESC")
    fun observeByType(eventType: String): Flow<List<AuditEventEntity>>

    @Insert
    suspend fun insert(event: AuditEventEntity): Long

    @Query("DELETE FROM audit_event WHERE occurred_at < :beforeEpoch")
    suspend fun deleteOlderThan(beforeEpoch: Long)

    @Query("SELECT * FROM audit_event ORDER BY sequence_number DESC, id DESC LIMIT 1")
    suspend fun getLatestEvent(): AuditEventEntity?

    @Query("SELECT * FROM audit_event WHERE sequence_number BETWEEN :startSeq AND :endSeq ORDER BY sequence_number ASC")
    suspend fun getEventsBySequenceRange(startSeq: Long, endSeq: Long): List<AuditEventEntity>

    @Query("SELECT COUNT(*) FROM audit_event")
    suspend fun getEventCount(): Long

    @Query("DELETE FROM audit_event")
    suspend fun deleteAll()
}
