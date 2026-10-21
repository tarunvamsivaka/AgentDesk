package com.agentdesk.knowledge.handler

import android.content.Intent
import android.net.Uri
import com.agentdesk.core.common.model.IndexStatus
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.DocumentEntity
import com.agentdesk.core.persistence.entity.EmbeddingVectorEntity
import com.agentdesk.core.persistence.entity.ExtractedEntityEntity
import com.agentdesk.core.persistence.entity.IndexJobEntity
import com.agentdesk.core.persistence.entity.KnowledgeSourceEntity
import com.agentdesk.core.persistence.entity.SharedItemEntity
import com.agentdesk.core.persistence.entity.TextChunkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private class FakeKnowledgeDao : KnowledgeDao {
    val sharedItems = mutableListOf<SharedItemEntity>()
    val extractedEntities = mutableListOf<ExtractedEntityEntity>()

    override fun observeSources(): Flow<List<KnowledgeSourceEntity>> = emptyFlow()
    override suspend fun findSourceById(id: Long): KnowledgeSourceEntity? = null
    override suspend fun insertSource(source: KnowledgeSourceEntity): Long = 1L
    override suspend fun updateSource(source: KnowledgeSourceEntity) {}
    override suspend fun deleteSource(source: KnowledgeSourceEntity) {}
    override suspend fun updateIndexStatus(sourceId: Long, status: IndexStatus) {}
    override suspend fun insertDocument(document: DocumentEntity): Long = 1L
    override suspend fun documentsForSource(sourceId: Long): List<DocumentEntity> = emptyList()
    override suspend fun insertChunks(chunks: List<TextChunkEntity>) {}
    override suspend fun chunksForDocument(documentId: Long): List<TextChunkEntity> = emptyList()
    override suspend fun findChunkById(chunkId: Long): TextChunkEntity? = null
    override suspend fun insertIndexJob(job: IndexJobEntity): Long = 1L
    override suspend fun updateIndexJob(job: IndexJobEntity) {}
    override suspend fun latestJobForSource(sourceId: Long): IndexJobEntity? = null
    override suspend fun insertEmbedding(embedding: EmbeddingVectorEntity): Long = 1L
    override suspend fun insertEmbeddings(embeddings: List<EmbeddingVectorEntity>) {}
    override suspend fun findEmbeddingForChunk(chunkId: Long): EmbeddingVectorEntity? = null
    override suspend fun getAllEmbeddingsWithBlobs(): List<EmbeddingVectorEntity> = emptyList()
    override suspend fun findChunksByIds(chunkIds: List<Long>): List<TextChunkEntity> = emptyList()
    override suspend fun getAllChunksWithQuantizedEmbeddings(): List<TextChunkEntity> = emptyList()

    override suspend fun insertSharedItem(item: SharedItemEntity): Long {
        sharedItems.add(item)
        return sharedItems.size.toLong()
    }

    override suspend fun insertExtractedEntity(entity: ExtractedEntityEntity): Long {
        extractedEntities.add(entity)
        return extractedEntities.size.toLong()
    }

    override fun observeSharedItems(limit: Int): Flow<List<SharedItemEntity>> = emptyFlow()
    override suspend fun entitiesForSharedItem(itemId: Long): List<ExtractedEntityEntity> =
        extractedEntities.filter { it.sharedItemId == itemId }

    override suspend fun deleteAllChunks() {}
    override suspend fun deleteAllDocuments() {}
    override suspend fun deleteAllSources() {}
    override suspend fun deleteAllSharedItems() {
        sharedItems.clear()
        extractedEntities.clear()
    }

    override suspend fun deleteSharedItemsOlderThan(beforeMs: Long) {}
    override suspend fun deleteExpiredEntities(nowMs: Long, cutoffMs: Long) {}
}

class SharedItemHandlerTest {

    private lateinit var fakeKnowledgeDao: FakeKnowledgeDao
    private lateinit var entityExtractor: EntityExtractor
    private lateinit var handler: SharedItemHandler

    @Before
    fun setUp() {
        fakeKnowledgeDao = FakeKnowledgeDao()
        entityExtractor = EntityExtractor()
        handler = SharedItemHandler(fakeKnowledgeDao, entityExtractor)
    }

    @Test
    fun testEntityExtractorExtractsAllSupportedTypes() {
        val sampleText = """
            Meeting scheduled for 2026-10-15T14:30:00.
            Call me at 555-123-4567 or email test@example.com.
            Visit https://agentdesk.ai for docs.
            Tracking number is 1Z9999999999999999.
        """.trimIndent()

        val entities = entityExtractor.extract(sampleText, sharedItemId = 42L)
        val typeMap = entities.groupBy { it.entityType }

        assertTrue(typeMap.containsKey("DATE_TIME"))
        assertTrue(typeMap.containsKey("PHONE"))
        assertTrue(typeMap.containsKey("EMAIL"))
        assertTrue(typeMap.containsKey("URL"))
        assertTrue(typeMap.containsKey("TRACKING_ID"))

        assertEquals("555-123-4567", typeMap["PHONE"]?.first()?.value)
        assertEquals("test@example.com", typeMap["EMAIL"]?.first()?.value)
        assertEquals("https://agentdesk.ai", typeMap["URL"]?.first()?.value)
        assertEquals("1Z9999999999999999", typeMap["TRACKING_ID"]?.first()?.value)
    }

    @Test(expected = SecurityException::class)
    fun testRejectFileUriScheme() = runBlocking {
        val intent = object : Intent(ACTION_SEND) {
            override fun getStringExtra(name: String?): String? {
                if (name == "EXTRA_URI_STRING") return "file:///etc/passwd"
                return null
            }
        }
        handler.handle(intent)
    }

    @Test
    fun testIngestSecurityValidatorRejectsTraversalAndPrivateDirs() {
        // file:// URI must be rejected
        org.junit.Assert.assertFalse(
            IngestSecurityValidator.isUriStringSafe(
                uriString = "file:///data/local/tmp/exploit.txt"
            )
        )

        // Traversal .. must be rejected
        org.junit.Assert.assertFalse(
            IngestSecurityValidator.isUriStringSafe(
                uriString = "content://com.agentdesk.provider/../../databases/agentdesk.db"
            )
        )

        // Databases / private path must be rejected
        org.junit.Assert.assertFalse(
            IngestSecurityValidator.isUriStringSafe(
                uriString = "content://com.agentdesk.provider/data/data/com.agentdesk/databases/agentdesk.db"
            )
        )

        // Normal external content URI must be allowed
        org.junit.Assert.assertTrue(
            IngestSecurityValidator.isUriStringSafe(
                uriString = "content://media/external/file/12345"
            )
        )

        // Magic bytes validation
        val docxFile = File.createTempFile("test_docx", ".docx").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00))
            deleteOnExit()
        }
        org.junit.Assert.assertTrue(IngestSecurityValidator.verifyMagicBytes(docxFile))

        val binaryFile = File.createTempFile("bad", ".bin").apply {
            writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x02))
            deleteOnExit()
        }
        org.junit.Assert.assertFalse(IngestSecurityValidator.verifyMagicBytes(binaryFile))
    }

    @Test
    fun testHandleImageShareWithStructuredMetadataIndexing() = runBlocking {
        val intent = object : Intent(ACTION_SEND) {
            override fun getType(): String = "image/png"
            override fun getStringExtra(name: String?): String? {
                return when (name) {
                    EXTRA_TEXT -> "Screenshot of invoice dated 2026-10-21"
                    "EXTRA_URI_STRING" -> "content://media/external/images/invoice_receipt.png"
                    else -> null
                }
            }
        }

        handler.handle(intent)

        assertEquals("Should insert 1 shared item", 1, fakeKnowledgeDao.sharedItems.size)
        val item = fakeKnowledgeDao.sharedItems.first()
        assertEquals("image", item.contentType)
        assertTrue(item.rawContent.contains("[Image: invoice_receipt.png | image/png]"))
        assertTrue(item.rawContent.contains("Screenshot of invoice dated 2026-10-21"))

        // Check structured metadata entities
        val entities = fakeKnowledgeDao.extractedEntities
        val mimeEntity = entities.find { it.entityType == "MIME_TYPE" }
        assertNotNull(mimeEntity)
        assertEquals("image/png", mimeEntity!!.value)

        val fileEntity = entities.find { it.entityType == "FILE_NAME" }
        assertNotNull(fileEntity)
        assertEquals("invoice_receipt.png", fileEntity!!.value)

        val dateEntity = entities.find { it.entityType == "DATE_TIME" }
        assertNotNull(dateEntity)
        assertEquals("2026-10-21", dateEntity!!.value)
    }
}
