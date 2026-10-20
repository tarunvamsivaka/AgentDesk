package com.agentdesk.knowledge.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastVectorMathTest {

    @Test
    fun serializationDeserialization_roundTripMatches() {
        val original = floatArrayOf(1.0f, -0.5f, 3.14159f, 0.0f, 42.0f, -100.25f, 0.0001f, 1234.5f)
        val bytes = FastVectorMath.toByteArray(original)

        assertEquals(original.size * 4, bytes.size)

        val deserialized = FastVectorMath.toFloatArray(bytes)
        assertEquals(original.size, deserialized.size)
        for (i in original.indices) {
            assertEquals(original[i], deserialized[i], 1e-6f)
        }
    }

    @Test
    fun dotProduct_computesAccurateResultsWithUnrolledLoop() {
        // Size 10: tests unrolled limit (8) + remainder (2)
        val a = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        val b = floatArrayOf(2f, 0.5f, 1f, 0f, -1f, 2f, 1f, -0.5f, 3f, 1f)

        // Expected: 1*2 + 2*0.5 + 3*1 + 4*0 + 5*(-1) + 6*2 + 7*1 + 8*(-0.5) + 9*3 + 10*1
        // = 2 + 1 + 3 + 0 - 5 + 12 + 7 - 4 + 27 + 10 = 53
        val dot = FastVectorMath.dotProduct(a, b)
        assertEquals(53.0f, dot, 1e-5f)
    }

    @Test
    fun l2NormAndNormalize_producesUnitVector() {
        val v = floatArrayOf(3.0f, 4.0f)
        assertEquals(5.0f, FastVectorMath.l2Norm(v), 1e-6f)

        val normalized = FastVectorMath.normalize(v)
        assertEquals(1.0f, FastVectorMath.l2Norm(normalized), 1e-6f)
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
    }

    @Test
    fun cosineSimilarity_identicalVectorsYieldOne() {
        val a = floatArrayOf(0.5f, 1.2f, -3.4f, 2.0f)
        val b = floatArrayOf(0.5f, 1.2f, -3.4f, 2.0f)

        val sim = FastVectorMath.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 1e-5f)
    }

    @Test
    fun cosineSimilarity_orthogonalVectorsYieldZero() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f)

        val sim = FastVectorMath.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dotProduct_mismatchedDimensionsThrows() {
        FastVectorMath.dotProduct(floatArrayOf(1f, 2f), floatArrayOf(1f))
    }

    @Test
    fun quantizeToInt8_andCosineSimilarityInt8_identicalVectorsYieldOne() {
        val a = FloatArray(768) { i -> (i % 17 - 8).toFloat() / 8.0f }
        val q1 = FastVectorMath.quantizeToInt8(a)
        val q2 = FastVectorMath.quantizeToInt8(a)

        val sim = FastVectorMath.cosineSimilarityInt8(
            q1.bytes, q1.scale, q1.norm,
            q2.bytes, q2.scale, q2.norm
        )
        // Quantized identical vectors should produce >= 0.999 cosine similarity
        assertTrue("Expected similarity close to 1.0, got $sim", sim > 0.999f)
    }

    @Test
    fun quantizeToInt8_andCosineSimilarityInt8_driftLessThanOnePercent() {
        val random = java.util.Random(42)
        val dim = 768
        val a = FloatArray(dim) { random.nextGaussian().toFloat() }
        val b = FloatArray(dim) { random.nextGaussian().toFloat() }

        val simFloat = FastVectorMath.cosineSimilarity(a, b)
        val qA = FastVectorMath.quantizeToInt8(a)
        val qB = FastVectorMath.quantizeToInt8(b)
        val simInt8 = FastVectorMath.cosineSimilarityInt8(
            qA.bytes, qA.scale, qA.norm,
            qB.bytes, qB.scale, qB.norm
        )

        val drift = kotlin.math.abs(simFloat - simInt8)
        assertTrue("Quantization drift $drift exceeds 1% (0.01)", drift < 0.01f)
    }

    @Test
    fun cosineSimilarityInt8_2000VectorsBenchmarkUnder40ms() {
        val random = java.util.Random(1337)
        val dim = 768
        val query = FloatArray(dim) { random.nextGaussian().toFloat() }
        val qQuery = FastVectorMath.quantizeToInt8(query)

        val corpusCount = 2000
        val corpus = List(corpusCount) {
            val v = FloatArray(dim) { random.nextGaussian().toFloat() }
            FastVectorMath.quantizeToInt8(v)
        }

        // Warm up
        for (i in 0 until 100) {
            FastVectorMath.cosineSimilarityInt8(
                qQuery.bytes, qQuery.scale, qQuery.norm,
                corpus[i].bytes, corpus[i].scale, corpus[i].norm
            )
        }

        val startNs = System.nanoTime()
        var checksum = 0f
        for (i in 0 until corpusCount) {
            checksum += FastVectorMath.cosineSimilarityInt8(
                qQuery.bytes, qQuery.scale, qQuery.norm,
                corpus[i].bytes, corpus[i].scale, corpus[i].norm
            )
        }
        val durationMs = (System.nanoTime() - startNs) / 1_000_000.0

        assertTrue(checksum != 0f) // Prevent dead-code elimination
        assertTrue("2,000 int8 vector scan took ${durationMs}ms, exceeding 40ms threshold", durationMs < 40.0)
    }
}
