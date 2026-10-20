package com.agentdesk.knowledge.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.ExtractedEntityEntity
import com.agentdesk.core.persistence.entity.SharedItemEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val entityExtractor: EntityExtractor,
    @ApplicationContext private val context: Context? = null
) {
    suspend fun handle(intent: Intent) = withContext(Dispatchers.IO) {
        val mimeType = intent.type ?: "text/plain"
        val rawText: String? = intent.getStringExtra(Intent.EXTRA_TEXT)
        val rawUriString: String? = intent.getStringExtra("EXTRA_URI_STRING") ?: intent.dataString

        val uri: Uri? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: intent.data

        // Security check via raw string (for unit testability & explicit URI strings)
        if (rawUriString != null) {
            if (rawUriString.startsWith("file:", ignoreCase = true)) {
                throw SecurityException("file:// URIs are rejected for security.")
            }
            if (!IngestSecurityValidator.isUriStringSafe(context, rawUriString)) {
                throw SecurityException("URI is insecure, traversal detected, or points to private storage.")
            }
        }

        // Security check: Confused Deputy & Traversal Prevention
        if (uri != null) {
            val uriStr = uri.toString()
            val scheme = uri.scheme?.lowercase() ?: uriStr.substringBefore(":", "").lowercase()
            if (scheme == "file" || uriStr.startsWith("file:", ignoreCase = true)) {
                throw SecurityException("file:// URIs are rejected for security.")
            }
            if (!IngestSecurityValidator.isUriSafe(context, uri)) {
                throw SecurityException("URI is insecure, traversal detected, or points to private storage.")
            }
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
            rawContent = rawText ?: rawUriString ?: uri?.toString() ?: "",
            sourcePackage = "", // Not exposed by Android for privacy
            receivedAt = System.currentTimeMillis(),
            processed = false
        )

        val itemId = knowledgeDao.insertSharedItem(sharedItem)

        // Extract entities from raw text (phones, emails, dates, URLs, tracking IDs)
        val contentToExtract = rawText ?: rawUriString ?: uri?.toString() ?: ""
        if (contentToExtract.isNotBlank()) {
            val entities = entityExtractor.extract(contentToExtract, itemId)
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
