package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.common.model.ConsentState
import com.agentdesk.core.persistence.entity.ConsentRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConsentDao {

    @Query("SELECT * FROM consent_record ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ConsentRecordEntity>>

    @Query("SELECT * FROM consent_record WHERE consent_key = :key LIMIT 1")
    suspend fun findByKey(key: String): ConsentRecordEntity?

    @Query("SELECT state FROM consent_record WHERE consent_key = :key LIMIT 1")
    suspend fun getState(key: String): ConsentState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ConsentRecordEntity)

    @Query("UPDATE consent_record SET state = :state, updated_at = :updatedAt WHERE consent_key = :key")
    suspend fun updateState(key: String, state: ConsentState, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM consent_record WHERE consent_key = :key")
    suspend fun deleteByKey(key: String)
}
