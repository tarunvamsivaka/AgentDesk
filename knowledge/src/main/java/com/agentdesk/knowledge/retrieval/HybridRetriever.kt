package com.agentdesk.knowledge.retrieval

import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.TextChunkEntity
import com.agentdesk.knowledge.vector.EmbeddingGenerator
import com.agentdesk.knowledge.vector.FastVectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HybridRetriever combines dense vector semantic search and sparse SQLite FTS4 BM25 search
 * using Reciprocal Rank Fusion (RRF, k=60).
 *
 * Scoring Formula:
 *   RRF_Score(d) = sum_{m in models} 1 / (k + rank_m(d))
 *
 * Latency SLA: < 100ms on modern ARM64 devices.
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
     * Executes hybrid retrieval combining FTS4 and Vector search.
     *
     * @param query Search terms
     * @param limit Maximum results to return
     * @param rrfK Smoothing constant (default 60)
     */
    suspend fun retrieve(
        query: String,
        limit: Int = 10,
        rrfK: Float = 60f
    ): List<ScoredChunk> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        coroutineScope {
            // Run FTS4 and Vector retrieval concurrently
            val ftsDeferred = async { ftsSearchDao.safeSearch(trimmed, limit = limit * 2) }
            val vectorDeferred = async { retrieveVectorCandidates(trimmed, limit = limit * 2) }

            val ftsChunks = ftsDeferred.await()
            val vectorChunks = vectorDeferred.await() // List<Pair<Long, Float>> (chunkId, cosineSim)

            // Reciprocal Rank Fusion
            val rrfScores = mutableMapOf<Long, Float>()
            val vectorScoreMap = vectorChunks.toMap()
            val ftsRankMap = mutableMapOf<Long, Int>()

            // 1. Accumulate FTS ranks
            ftsChunks.forEachIndexed { rank, chunk ->
                val rrf = 1.0f / (rrfK + (rank + 1))
                rrfScores[chunk.id] = (rrfScores[chunk.id] ?: 0f) + rrf
                ftsRankMap[chunk.id] = rank + 1
            }

            // 2. Accumulate Vector ranks
            vectorChunks.forEachIndexed { rank, (chunkId, _) ->
                val rrf = 1.0f / (rrfK + (rank + 1))
                rrfScores[chunkId] = (rrfScores[chunkId] ?: 0f) + rrf
            }

            if (rrfScores.isEmpty()) {
                return@coroutineScope emptyList()
            }

            // Rank combined candidate IDs by RRF score descending
            val topChunkIds = rrfScores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }

            // Fetch any chunks not already in memory from FTS
            val chunkEntityMap = ftsChunks.associateBy { it.id }.toMutableMap()
            val missingIds = topChunkIds.filter { !chunkEntityMap.containsKey(it) }
            if (missingIds.isNotEmpty()) {
                val fetched = knowledgeDao.findChunksByIds(missingIds)
                fetched.forEach { chunkEntityMap[it.id] = it }
            }

            topChunkIds.mapNotNull { id ->
                val entity = chunkEntityMap[id] ?: return@mapNotNull null
                ScoredChunk(
                    chunk = entity,
                    rrfScore = rrfScores[id] ?: 0f,
                    vectorScore = vectorScoreMap[id] ?: 0f,
                    ftsRank = ftsRankMap[id] ?: -1
                )
            }
        }
    }

    private suspend fun retrieveVectorCandidates(
        query: String,
        limit: Int
    ): List<Pair<Long, Float>> {
        val queryVector = embeddingGenerator.generateEmbedding(query)
        val allEmbeddings = knowledgeDao.getAllEmbeddingsWithBlobs()
        if (allEmbeddings.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<Long, Float>>()
        for (emb in allEmbeddings) {
            val blob = emb.vectorBlob ?: continue
            val chunkVector = FastVectorMath.toFloatArray(blob)
            // Dimension safety check: guard against dimension mismatch across embedding model versions
            if (chunkVector.size != queryVector.size) continue
            val score = FastVectorMath.dotProduct(queryVector, chunkVector)
            scored.add(emb.chunkId to score)
        }

        return scored
            .sortedByDescending { it.second }
            .take(limit)
    }
}
