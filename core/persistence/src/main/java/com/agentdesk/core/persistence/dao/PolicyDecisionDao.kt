package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDecisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(decision: PolicyDecisionEntity): Long

    @Query("SELECT * FROM policy_decision ORDER BY decided_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<PolicyDecisionEntity>>

    @Query("SELECT * FROM policy_decision WHERE intent_name = :intent ORDER BY decided_at DESC LIMIT :limit")
    fun observeByIntent(intent: String, limit: Int = 50): Flow<List<PolicyDecisionEntity>>

    @Query("SELECT * FROM policy_decision WHERE allowed = 0 ORDER BY decided_at DESC LIMIT :limit")
    fun observeRejected(limit: Int = 50): Flow<List<PolicyDecisionEntity>>

    @Query("DELETE FROM policy_decision WHERE decided_at < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM policy_decision")
    suspend fun count(): Int
}
