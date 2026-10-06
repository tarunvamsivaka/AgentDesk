package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import com.agentdesk.core.persistence.entity.ConversationThreadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for multi-turn conversation threads and messages.
 *
 * All writes must be executed on Dispatchers.IO.
 * Read queries support reactive Flow emissions for UI state synchronization.
 */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: ConversationThreadEntity): Long

    @Update
    suspend fun updateThread(thread: ConversationThreadEntity)

    @Query("SELECT * FROM conversation_thread WHERE id = :threadId")
    suspend fun getThread(threadId: Long): ConversationThreadEntity?

    @Query("SELECT * FROM conversation_thread WHERE is_archived = 0 ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestActiveThread(): ConversationThreadEntity?

    @Query("UPDATE conversation_thread SET updated_at = :updatedAt WHERE id = :threadId")
    suspend fun touchThread(threadId: Long, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationMessageEntity): Long

    @Query("SELECT * FROM conversation_message WHERE thread_id = :threadId ORDER BY created_at ASC, id ASC")
    fun observeMessages(threadId: Long): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_message WHERE thread_id = :threadId ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(threadId: Long, limit: Int): List<ConversationMessageEntity>

    @Query("DELETE FROM conversation_message WHERE thread_id = :threadId")
    suspend fun clearThread(threadId: Long)

    @Query("DELETE FROM conversation_thread WHERE updated_at < :cutoffMs AND is_archived = 1")
    suspend fun deleteArchivedOlderThan(cutoffMs: Long)

    @Query("DELETE FROM conversation_thread WHERE updated_at < :cutoffMs")
    suspend fun deleteThreadsOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM conversation_message WHERE thread_id = :threadId")
    suspend fun countMessages(threadId: Long): Int
}
