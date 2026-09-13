package com.agentdesk.core.persistence.dao

import androidx.room.Dao
import androidx.room.Query
import com.agentdesk.core.persistence.entity.TextChunkEntity

@Dao
interface FtsSearchDao {

    /**
     * Full-text search using SQLite FTS4.
     * Uses the [com.agentdesk.core.persistence.search.FtsQueryBuilder] to sanitize input
     * before this query is called.
     *
     * Returns matched TextChunkEntity rows joined via the content table.
     */
    @Query(
        """
        SELECT tc.* FROM text_chunk tc
        INNER JOIN text_chunk_fts fts ON tc.id = fts.rowid
        WHERE text_chunk_fts MATCH :sanitizedQuery
        ORDER BY tc.document_id ASC
        LIMIT :limit
        """
    )
    suspend fun searchChunks(sanitizedQuery: String, limit: Int = 20): List<TextChunkEntity>

    /**
     * Safe search: accepts raw user input, sanitizes via FtsQueryBuilder before executing.
     */
    suspend fun safeSearch(rawQuery: String, limit: Int = 20): List<TextChunkEntity> {
        val sanitized = com.agentdesk.core.persistence.search.FtsQueryBuilder.buildQuery(rawQuery)
        return if (sanitized.isBlank()) emptyList()
        else searchChunks(sanitized, limit)
    }

    /** Returns total number of indexed text chunks — used for index budget reporting. */
    @Query("SELECT COUNT(*) FROM text_chunk")
    suspend fun countChunks(): Int
}
