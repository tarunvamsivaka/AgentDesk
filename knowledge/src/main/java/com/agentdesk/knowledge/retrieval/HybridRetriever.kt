package com.agentdesk.knowledge.retrieval

import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.TextChunkEntity
import com.agentdesk.knowledge.vector.EmbeddingGenerator
import com.agentdesk.knowledge.vector.FastVectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HybridRetriever implements two-stage retrieval combining dense vector semantic search
 * with quantized int8 SIMD arithmetic and sparse SQLite FTS BM25 search using Reciprocal
 * Rank Fusion (RRF, k=60).
 *
 * Stage 1: Candidate pre-filtering via FTS Lexical Search (K_lex = 200).
 * Stage 2: Quantized Vector Re-ranking & Fast Scoring with loop unrolling (< 5ms for 200 chunks).
 * Fallback: Full scan of stored quantized embeddings if lexical pre-filtering returns no matches.
 *
 * Scoring Formula:
 *   RRF_Score(d) = 1.0 / (k + r_lex(d)) + 1.0 / (k + r_vec(d))
 *
 * Latency SLA: P95 < 40ms on modern ARM64 devices for 2,000 chunks.
 * Architectural Guarantee: 100% on-device, zero cloud egress.
 */
@Singleton
class HybridRetriever @Inject constructor(
    private val ftsSearchDao: FtsSearchDao,
    private val knowledgeDao: KnowledgeDao,
    private val embeddingGenerator: EmbeddingGenerator
) {

    data class ScoredChunk(
        val chunk: TextChunkEntity,
        val rrfScore: Float,
        val vectorScore: Float = 0f,
        val ftsRank: Int = -1
    )

    /**
     * Executes two-stage hybrid retrieval combining FTS and Quantized Vector search.
     *
     * @param query Search terms
     * @param limit Maximum results to return (default 10)
     * @param rrfK Smoothing constant (default 60f)
     * @param kLex Maximum candidate chunks pre-filtered by FTS (default 200)
     */
    suspend fun retrieve(
        query: String,
        limit: Int = 10,
        rrfK: Float = 60f,
        kLex: Int = 200
    ): List<ScoredChunk> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        // Stage 1: Candidate pre-filtering via FTS
        val ftsCandidates = ftsSearchDao.safeSearch(trimmed, limit = kLex)

        // Generate query embedding & quantize to int8
        val queryVector = embeddingGenerator.generateEmbedding(trimmed)
        val qQuery = FastVectorMath.quantizeToInt8(queryVector)

        if (ftsCandidates.isNotEmpty()) {
            // Stage 2: Quantized Vector Re-ranking & Scoring on FTS candidates
            val candidateChunks = ftsCandidates.toMutableList()

            // If FTS returned fewer results than limit, supplement with top vector matches if available
            if (candidateChunks.size < limit) {
                val existingIds = candidateChunks.map { it.id }.toSet()
                val extraChunks = knowledgeDao.getAllChunksWithQuantizedEmbeddings()
                    .filter { !existingIds.contains(it.id) }
                if (extraChunks.isNotEmpty()) {
                    candidateChunks.addAll(extraChunks)
                }
            }

            // Score candidates via quantized SIMD cosine similarity
            val vectorScores = mutableMapOf<Long, Float>()
            for (chunk in candidateChunks) {
                val qEmb = chunk.quantizedEmbedding
                if (qEmb != null) {
                    if (qEmb.size == qQuery.bytes.size) {
                        vectorScores[chunk.id] = FastVectorMath.cosineSimilarityInt8(
                            qQuery.bytes, qQuery.scale, qQuery.norm,
                            qEmb, chunk.quantizationScale, chunk.vectorNorm
                        )
                    }
                } else {
                    // Fallback for legacy float32 blob in embedding_vector table
                    val emb = knowledgeDao.findEmbeddingForChunk(chunk.id)
                    val blob = emb?.vectorBlob
                    if (blob != null) {
                        val chunkVector = FastVectorMath.toFloatArray(blob)
                        if (chunkVector.size == queryVector.size) {
                            vectorScores[chunk.id] = FastVectorMath.dotProduct(queryVector, chunkVector)
                        }
                    }
                }
            }

            // Rank candidates by vector score descending (1-based rank)
            val sortedByVector = candidateChunks
                .filter { vectorScores.containsKey(it.id) }
                .sortedByDescending { vectorScores[it.id] ?: 0f }
            val vectorRankMap = sortedByVector.mapIndexed { idx, c -> c.id to (idx + 1) }.toMap()

            // Map FTS rank (1-based rank)
            val ftsRankMap = ftsCandidates.mapIndexed { idx, c -> c.id to (idx + 1) }.toMap()

            // Reciprocal Rank Fusion
            val rrfScores = mutableMapOf<Long, Float>()
            val chunkMap = candidateChunks.associateBy { it.id }

            for (chunk in candidateChunks) {
                val ftsRank = ftsRankMap[chunk.id]
                val vecRank = vectorRankMap[chunk.id]

                var score = 0f
                if (ftsRank != null) {
                    score += 1.0f / (rrfK + ftsRank)
                }
                if (vecRank != null) {
                    score += 1.0f / (rrfK + vecRank)
                }
                rrfScores[chunk.id] = score
            }

            val topIds = rrfScores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }

            topIds.mapNotNull { id ->
                val entity = chunkMap[id] ?: return@mapNotNull null
                ScoredChunk(
                    chunk = entity,
                    rrfScore = rrfScores[id] ?: 0f,
                    vectorScore = vectorScores[id] ?: 0f,
                    ftsRank = ftsRankMap[id] ?: -1
                )
            }
        } else {
            // Fallback: No lexical candidates matched -> scan all stored quantized embeddings
            val allQuantized = knowledgeDao.getAllChunksWithQuantizedEmbeddings()
            if (allQuantized.isNotEmpty()) {
                val scored = allQuantized.mapNotNull { chunk ->
                    val qEmb = chunk.quantizedEmbedding ?: return@mapNotNull null
                    if (qEmb.size != qQuery.bytes.size) return@mapNotNull null
                    val sim = FastVectorMath.cosineSimilarityInt8(
                        qQuery.bytes, qQuery.scale, qQuery.norm,
                        qEmb, chunk.quantizationScale, chunk.vectorNorm
                    )
                    chunk to sim
                }.sortedByDescending { it.second }.take(limit)

                scored.mapIndexed { rank, (chunk, vScore) ->
                    ScoredChunk(
                        chunk = chunk,
                        rrfScore = 1.0f / (rrfK + (rank + 1)),
                        vectorScore = vScore,
                        ftsRank = -1
                    )
                }
            } else {
                // Secondary fallback: check legacy embedding_vector table
                val legacyCandidates = retrieveLegacyVectorCandidates(queryVector, limit)
                if (legacyCandidates.isEmpty()) return@withContext emptyList()

                val chunkIds = legacyCandidates.map { it.first }
                val fetched = knowledgeDao.findChunksByIds(chunkIds).associateBy { it.id }

                legacyCandidates.mapIndexedNotNull { rank, (chunkId, vScore) ->
                    val entity = fetched[chunkId] ?: return@mapIndexedNotNull null
                    ScoredChunk(
                        chunk = entity,
                        rrfScore = 1.0f / (rrfK + (rank + 1)),
                        vectorScore = vScore,
                        ftsRank = -1
                    )
                }
            }
        }
    }

    private suspend fun retrieveLegacyVectorCandidates(
        queryVector: FloatArray,
        limit: Int
    ): List<Pair<Long, Float>> {
        val allEmbeddings = knowledgeDao.getAllEmbeddingsWithBlobs()
        if (allEmbeddings.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<Long, Float>>()
        for (emb in allEmbeddings) {
            val blob = emb.vectorBlob ?: continue
            val chunkVector = FastVectorMath.toFloatArray(blob)
            if (chunkVector.size != queryVector.size) continue
            val score = FastVectorMath.dotProduct(queryVector, chunkVector)
            scored.add(emb.chunkId to score)
        }

        return scored
            .sortedByDescending { it.second }
            .take(limit)
    }
}
