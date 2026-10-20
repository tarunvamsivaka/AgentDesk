package com.agentdesk.knowledge.vector

/**
 * Interface for generating dense float embeddings locally on-device.
 */
interface EmbeddingGenerator {
    val modelVersion: String
    val dimension: Int
    suspend fun generateEmbedding(text: String): FloatArray
    suspend fun generateEmbeddings(texts: List<String>): List<FloatArray> {
        return texts.map { generateEmbedding(it) }
    }
}

/**
 * High-performance deterministic hashing-based local embedder.
 * Generates unit L2-normalized 128-dimensional dense vectors using word feature hashing
 * and n-gram subword positional encoding.
 *
 * Runs 100% on CPU, zero cloud egress, zero NDK, deterministic, sub-millisecond execution.
 */
class HashEmbeddingGenerator(
    override val dimension: Int = 128,
    override val modelVersion: String = "local-hash-v1"
) : EmbeddingGenerator {

    override suspend fun generateEmbeddings(texts: List<String>): List<FloatArray> {
        return texts.map { generateEmbedding(it) }
    }

    override suspend fun generateEmbedding(text: String): FloatArray {
        val vector = FloatArray(dimension)
        val tokens = text.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length > 1 }

        if (tokens.isEmpty()) {
            return vector
        }

        // Token frequency and position weighting
        tokens.forEachIndexed { pos, token ->
            val h1 = (token.hashCode() and 0x7FFFFFFF) % dimension
            val h2 = ((token.hashCode() * 31 + pos) and 0x7FFFFFFF) % dimension
            val sign1 = if ((token.hashCode() and 1) == 0) 1.0f else -1.0f
            val sign2 = if ((pos and 1) == 0) 0.5f else -0.5f

            vector[h1] += sign1
            vector[h2] += sign2

            // Subword 3-grams
            if (token.length >= 3) {
                for (i in 0..token.length - 3) {
                    val tri = token.substring(i, i + 3)
                    val hTri = (tri.hashCode() and 0x7FFFFFFF) % dimension
                    vector[hTri] += 0.3f
                }
            }
        }

        return FastVectorMath.normalize(vector)
    }
}
