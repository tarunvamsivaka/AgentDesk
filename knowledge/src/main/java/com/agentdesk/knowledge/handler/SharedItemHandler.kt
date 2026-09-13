package com.agentdesk.knowledge.handler

import android.content.Intent
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.ExtractedEntityEntity
import com.agentdesk.core.persistence.entity.SharedItemEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedItemHandler — receives content from ShareReceiverActivity and persists it locally.
 *
 * Privacy: no data leaves the device. All extraction is local regex-based.
 * Content is surfaced in the Library screen for user review.
 */
@Singleton
class SharedItemHandler @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val entityExtractor: EntityExtractor
) {
    suspend fun handle(intent: Intent) {
        val mimeType = intent.type ?: "text/plain"
        val rawText: String? = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        // Determine content type
        val contentType = when {
            mimeType.startsWith("image/") -> "image"
            mimeType == "application/pdf" -> "pdf"
            uri != null -> "file"
            else -> "text"
        }

        val sharedItem = SharedItemEntity(
            contentType = contentType,
            rawContent = rawText ?: uri?.toString() ?: "",
            sourcePackage = "", // Not exposed by Android for privacy
            receivedAt = System.currentTimeMillis(),
            processed = false
        )
        val itemId = knowledgeDao.insertSharedItem(sharedItem)

        // Extract entities from text content (local regex only)
        if (!rawText.isNullOrBlank()) {
            val entities = entityExtractor.extract(rawText, itemId)
            entities.forEach { knowledgeDao.insertExtractedEntity(it) }
        }
    }
}

/**
 * EntityExtractor — applies regex patterns to raw text and returns ExtractedEntityEntity records.
 *
 * Supported entity types: DATE_TIME, PHONE, EMAIL, URL, TRACKING_ID
 */
@Singleton
class EntityExtractor @Inject constructor() {

    fun extract(text: String, sharedItemId: Long): List<ExtractedEntityEntity> {
        val results = mutableListOf<ExtractedEntityEntity>()
        val now = System.currentTimeMillis()

        PATTERNS.forEach { (type, regex) ->
            regex.findAll(text).forEach { match ->
                results.add(
                    ExtractedEntityEntity(
                        sharedItemId = sharedItemId,
                        entityType = type,
                        value = match.value.trim(),
                        confidence = 1.0f,
                        extractedAt = now
                    )
                )
            }
        }
        return results
    }

    companion object {
        private val PATTERNS = mapOf(
            "DATE_TIME"    to Regex("""(\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2})?)?|\d{1,2}/\d{1,2}/\d{2,4})"""),
            "PHONE"        to Regex("""(\+?1?\s*[-.]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4})"""),
            "EMAIL"        to Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
            "URL"          to Regex("""https?://[^\s<>"{}|\\^`\[\]]+"""),
            "TRACKING_ID"  to Regex("""\b([A-Z]{2,4}\d{8,20}|1Z[A-Z0-9]{16}|\d{20,22})\b""")
        )
    }
}
