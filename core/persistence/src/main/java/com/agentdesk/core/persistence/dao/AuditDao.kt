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

    @Query("DELETE FROM audit_event")
    suspend fun deleteAll()
}
