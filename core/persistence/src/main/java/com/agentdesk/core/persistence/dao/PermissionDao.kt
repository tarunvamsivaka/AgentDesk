package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.PermissionStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionDao {

    @Query("SELECT * FROM permission_state ORDER BY permission_key ASC")
    fun observeAll(): Flow<List<PermissionStateEntity>>

    @Query("SELECT * FROM permission_state WHERE permission_key = :key LIMIT 1")
    suspend fun findByKey(key: String): PermissionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PermissionStateEntity)

    @Query("UPDATE permission_state SET is_granted = :granted, last_checked_at = :checkedAt WHERE permission_key = :key")
    suspend fun updateGrant(key: String, granted: Boolean, checkedAt: Long = System.currentTimeMillis())
}
