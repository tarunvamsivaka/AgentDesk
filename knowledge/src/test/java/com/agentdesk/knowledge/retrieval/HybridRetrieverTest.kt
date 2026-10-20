package com.agentdesk.knowledge.retrieval

import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.*
import com.agentdesk.knowledge.vector.EmbeddingGenerator
import com.agentdesk.knowledge.vector.FastVectorMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HybridRetrieverTest {

    private lateinit var mockFtsDao: MockFtsSearchDao
    private lateinit var mockKnowledgeDao: MockKnowledgeDao
    private lateinit var mockEmbeddingGen: MockEmbeddingGenerator
    private lateinit var retriever: HybridRetriever

    @Before
    fun setup() {
        mockFtsDao = MockFtsSearchDao()
        mockKnowledgeDao = MockKnowledgeDao()
        mockEmbeddingGen = MockEmbeddingGenerator()
        retriever = HybridRetriever(mockFtsDao, mockKnowledgeDao, mockEmbeddingGen)
    }

    @Test
    fun retrieve_fusesFtsAndVectorViaRrf() = runBlocking {
        // Prepare 2 chunks
        val chunk1 = TextChunkEntity(id = 101, documentId = 1, chunkIndex = 0, content = "Passport expiration and renewal guide")
        val chunk2 = TextChunkEntity(id = 102, documentId = 1, chunkIndex = 1, content = "Flight ticket to Tokyo Japan")

        mockKnowledgeDao.storedChunks[101] = chunk1
        mockKnowledgeDao.storedChunks[102] = chunk2

        // FTS returns chunk 101 at rank 0
        mockFtsDao.results = listOf(chunk1)

        // Vector has embeddings for both: chunk 102 gets higher similarity, chunk 101 gets moderate similarity
        val vec1 = FastVectorMath.toByteArray(floatArrayOf(0.5f, 0.5f))
        val vec2 = FastVectorMath.toByteArray(floatArrayOf(1.0f, 0.0f))
        mockKnowledgeDao.storedEmbeddings.add(EmbeddingVectorEntity(id = 1, chunkId = 101, vectorBlob = vec1))
        mockKnowledgeDao.storedEmbeddings.add(EmbeddingVectorEntity(id = 2, chunkId = 102, vectorBlob = vec2))

        val results = retriever.retrieve("passport flight", limit = 5, rrfK = 60f)

        assertFalse(results.isEmpty())
        assertTrue(results.any { it.chunk.id == 101L })
        // chunk101 was present in both FTS (rank 1) and Vector -> should have strong combined RRF score
        val c101 = results.first { it.chunk.id == 101L }
        assertTrue(c101.rrfScore > 0f)
    }

    @Test
    fun retrieve_emptyQueryReturnsEmpty() = runBlocking {
        val results = retriever.retrieve("   ")
        assertTrue(results.isEmpty())
    }

    @Test
    fun retrieve_skipsDimensionMismatchedEmbeddingsWithoutCrashing() = runBlocking {
        val chunk1 = TextChunkEntity(id = 101, documentId = 1, chunkIndex = 0, content = "Passport text")
        mockKnowledgeDao.storedChunks[101] = chunk1

        // Vector 1 has matching dimension (2), Vector 2 has mismatched dimension (128)
        val vec1 = FastVectorMath.toByteArray(floatArrayOf(0.5f, 0.5f))
        val vecMismatched = FastVectorMath.toByteArray(FloatArray(128) { 0.1f })
        mockKnowledgeDao.storedEmbeddings.add(EmbeddingVectorEntity(id = 1, chunkId = 101, vectorBlob = vec1))
        mockKnowledgeDao.storedEmbeddings.add(EmbeddingVectorEntity(id = 2, chunkId = 102, vectorBlob = vecMismatched))

        // Must execute cleanly and not throw IllegalArgumentException
        val results = retriever.retrieve("passport", limit = 5)
        assertFalse(results.isEmpty())
        assertTrue(results.any { it.chunk.id == 101L })
    }

    @Test
    fun retrieve_withQuantizedEmbeddings_scoresAccuratelyViaRrf() = runBlocking {
        val q1 = FastVectorMath.quantizeToInt8(floatArrayOf(0.8f, 0.6f))
        val q2 = FastVectorMath.quantizeToInt8(floatArrayOf(-0.6f, 0.8f))

        val chunk1 = TextChunkEntity(
            id = 201, documentId = 1, chunkIndex = 0, content = "Tax return 2025 document",
            quantizedEmbedding = q1.bytes, quantizationScale = q1.scale, vectorNorm = q1.norm
        )
        val chunk2 = TextChunkEntity(
            id = 202, documentId = 1, chunkIndex = 1, content = "Grocery shopping milk eggs",
            quantizedEmbedding = q2.bytes, quantizationScale = q2.scale, vectorNorm = q2.norm
        )

        mockKnowledgeDao.storedChunks[201] = chunk1
        mockKnowledgeDao.storedChunks[202] = chunk2
        mockFtsDao.results = listOf(chunk1, chunk2)

        val results = retriever.retrieve("tax return", limit = 2)
        assertEquals(2, results.size)
        assertEquals(201L, results[0].chunk.id)
        assertTrue("Expected chunk 201 to have higher RRF score", results[0].rrfScore > results[1].rrfScore)
    }

    @Test
    fun retrieve_benchmark2000QuantizedChunksUnder40ms() = runBlocking {
        val random = java.util.Random(999)
        val corpusSize = 2000
        val dim = 2 // matching MockEmbeddingGenerator dimension

        val mockCorpus = List(corpusSize) { idx ->
            val v = floatArrayOf(random.nextFloat(), random.nextFloat())
            val q = FastVectorMath.quantizeToInt8(v)
            TextChunkEntity(
                id = idx.toLong() + 1000L,
                documentId = 1L,
                chunkIndex = idx,
                content = "Corpus chunk $idx",
                quantizedEmbedding = q.bytes,
                quantizationScale = q.scale,
                vectorNorm = q.norm
            )
        }

        mockCorpus.forEach { mockKnowledgeDao.storedChunks[it.id] = it }
        // Pre-filter returns 200 candidates
        mockFtsDao.results = mockCorpus.take(200)

        // Warmup
        retriever.retrieve("warmup query", limit = 10)

        // Benchmark
        val startNs = System.nanoTime()
        val results = retriever.retrieve("benchmark query", limit = 10)
        val durationMs = (System.nanoTime() - startNs) / 1_000_000.0

        assertEquals(10, results.size)
        assertTrue("Retrieval of 2000 chunks took ${durationMs}ms, exceeding 40ms SLA", durationMs < 40.0)
    }

    // --- Mock Implementations ---

    class MockFtsSearchDao : FtsSearchDao {
        var results = listOf<TextChunkEntity>()
        override suspend fun searchChunks(sanitizedQuery: String, limit: Int): List<TextChunkEntity> = results
        override suspend fun countChunks(): Int = results.size
    }

    class MockEmbeddingGenerator : EmbeddingGenerator {
        override val modelVersion: String = "test-mock"
        override val dimension: Int = 2
        override suspend fun generateEmbedding(text: String): FloatArray = floatArrayOf(0.8f, 0.6f)
    }

    class MockKnowledgeDao : KnowledgeDao {
        val storedChunks = mutableMapOf<Long, TextChunkEntity>()
        val storedEmbeddings = mutableListOf<EmbeddingVectorEntity>()

        override suspend fun getAllEmbeddingsWithBlobs(): List<EmbeddingVectorEntity> = storedEmbeddings

        override suspend fun findChunksByIds(chunkIds: List<Long>): List<TextChunkEntity> {
            return chunkIds.mapNotNull { storedChunks[it] }
        }

        override suspend fun getAllChunksWithQuantizedEmbeddings(): List<TextChunkEntity> {
            return storedChunks.values.filter { it.quantizedEmbedding != null }
        }

        override fun observeSources(): Flow<List<KnowledgeSourceEntity>> = emptyFlow()
        override suspend fun findSourceById(id: Long): KnowledgeSourceEntity? = null
        override suspend fun insertSource(source: KnowledgeSourceEntity): Long = 1L
        override suspend fun updateSource(source: KnowledgeSourceEntity) {}
        override suspend fun deleteSource(source: KnowledgeSourceEntity) {}
        override suspend fun updateIndexStatus(sourceId: Long, status: com.agentdesk.core.common.model.IndexStatus) {}
        override suspend fun insertDocument(document: DocumentEntity): Long = 1L
        override suspend fun documentsForSource(sourceId: Long): List<DocumentEntity> = emptyList()
        override suspend fun insertChunks(chunks: List<TextChunkEntity>) {}
        override suspend fun chunksForDocument(documentId: Long): List<TextChunkEntity> = storedChunks.values.toList()
        override suspend fun findChunkById(chunkId: Long): TextChunkEntity? = storedChunks[chunkId]
        override suspend fun insertIndexJob(job: IndexJobEntity): Long = 1L
        override suspend fun updateIndexJob(job: IndexJobEntity) {}
        override suspend fun latestJobForSource(sourceId: Long): IndexJobEntity? = null
        override suspend fun insertEmbedding(embedding: EmbeddingVectorEntity): Long = 1L
        override suspend fun insertEmbeddings(embeddings: List<EmbeddingVectorEntity>) { storedEmbeddings.addAll(embeddings) }
        override suspend fun findEmbeddingForChunk(chunkId: Long): EmbeddingVectorEntity? = storedEmbeddings.find { it.chunkId == chunkId }
        override suspend fun insertSharedItem(item: SharedItemEntity): Long = 1L
        override suspend fun insertExtractedEntity(entity: ExtractedEntityEntity): Long = 1L
        override fun observeSharedItems(limit: Int): Flow<List<SharedItemEntity>> = emptyFlow()
        override suspend fun entitiesForSharedItem(itemId: Long): List<ExtractedEntityEntity> = emptyList()
        override suspend fun deleteAllChunks() {}
        override suspend fun deleteAllDocuments() {}
        override suspend fun deleteAllSources() {}
        override suspend fun deleteAllSharedItems() {}
        override suspend fun deleteSharedItemsOlderThan(beforeMs: Long) {}
        override suspend fun deleteExpiredEntities(nowMs: Long, cutoffMs: Long) {}
    }
}
