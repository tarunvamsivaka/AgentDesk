package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.common.model.IndexStatus
import com.agentdesk.core.persistence.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {

    // Sources
    @Query("SELECT * FROM knowledge_source ORDER BY added_at DESC")
    fun observeSources(): Flow<List<KnowledgeSourceEntity>>

    @Query("SELECT * FROM knowledge_source WHERE id = :id LIMIT 1")
    suspend fun findSourceById(id: Long): KnowledgeSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: KnowledgeSourceEntity): Long

    @Update
    suspend fun updateSource(source: KnowledgeSourceEntity)

    @Delete
    suspend fun deleteSource(source: KnowledgeSourceEntity)

    @Query("UPDATE knowledge_source SET index_status = :status WHERE id = :sourceId")
    suspend fun updateIndexStatus(sourceId: Long, status: IndexStatus)

    // Documents
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("SELECT * FROM document WHERE source_id = :sourceId")
    suspend fun documentsForSource(sourceId: Long): List<DocumentEntity>

    // Text chunks
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<TextChunkEntity>)

    @Query("SELECT * FROM text_chunk WHERE document_id = :documentId ORDER BY chunk_index ASC")
    suspend fun chunksForDocument(documentId: Long): List<TextChunkEntity>

    @Query("SELECT * FROM text_chunk WHERE id = :chunkId LIMIT 1")
    suspend fun findChunkById(chunkId: Long): TextChunkEntity?

    // Index jobs
    @Insert
    suspend fun insertIndexJob(job: IndexJobEntity): Long

    @Update
    suspend fun updateIndexJob(job: IndexJobEntity)

    @Query("SELECT * FROM index_job WHERE source_id = :sourceId ORDER BY started_at DESC LIMIT 1")
    suspend fun latestJobForSource(sourceId: Long): IndexJobEntity?

    // ─── Share Bridge ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSharedItem(item: SharedItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtractedEntity(entity: ExtractedEntityEntity): Long

    @Query("SELECT * FROM shared_item ORDER BY received_at DESC LIMIT :limit")
    fun observeSharedItems(limit: Int = 50): Flow<List<SharedItemEntity>>

    @Query("SELECT * FROM extracted_entity WHERE shared_item_id = :itemId")
    suspend fun entitiesForSharedItem(itemId: Long): List<ExtractedEntityEntity>
}
