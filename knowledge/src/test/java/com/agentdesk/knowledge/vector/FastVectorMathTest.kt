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
}
