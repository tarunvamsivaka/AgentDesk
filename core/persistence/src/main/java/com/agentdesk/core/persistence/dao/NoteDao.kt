package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM note ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<NoteEntity>>

    @Query("DELETE FROM note WHERE created_at < :beforeEpoch")
    suspend fun deleteOlderThan(beforeEpoch: Long)
}
