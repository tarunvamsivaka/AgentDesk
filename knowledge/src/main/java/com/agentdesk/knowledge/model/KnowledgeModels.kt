package com.agentdesk.knowledge.model

import com.agentdesk.core.common.model.KnowledgeSourceType

/**
 * UI-facing representation of a knowledge source.
 */
data class KnowledgeSourceUi(
    val id: Long,
    val displayName: String,
    val sourceType: KnowledgeSourceType,
    val fileSizeBytes: Long,
    val indexStatusLabel: String,
    val addedAtMs: Long
)

/**
 * UI-facing representation of a search result.
 */
data class SearchResult(
    val chunkId: Long,
    val documentId: Long,
    val sourceId: Long,
    val sourceName: String,
    val content: String,
    val chunkIndex: Int,
    val pageNumber: Int
)

/**
 * Request to import a new document source.
 */
data class ImportRequest(
    val uri: String,
    val displayName: String,
    val sourceType: KnowledgeSourceType,
    val fileSizeBytes: Long = 0L
)

/**
 * Status of an ongoing import/index operation.
 */
sealed class ImportStatus {
    object Queued : ImportStatus()
    data class InProgress(val progress: Float) : ImportStatus()
    object Completed : ImportStatus()
    data class Failed(val reason: String) : ImportStatus()
}
