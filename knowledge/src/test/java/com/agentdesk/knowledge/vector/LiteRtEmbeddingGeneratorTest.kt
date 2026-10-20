package com.agentdesk.knowledge.vector

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class LiteRtEmbeddingGeneratorTest {

    private class FakeContext : android.content.ContextWrapper(null)

    @Test
    fun testDimensionAndModelVersion() {
        val generator = LiteRtEmbeddingGenerator(FakeContext())
        assertEquals(384, generator.dimension)
        assertEquals("bge-small-en-v1.5-int8", generator.modelVersion)
    }

    @Test
    fun testGenerateEmbeddingProducesUnitL2NormalizedVector() = runBlocking {
        val generator = LiteRtEmbeddingGenerator(FakeContext())
        val text = "Privacy-first on-device AI copilot with zero cloud egress"
        val vec = generator.generateEmbedding(text)

        assertEquals(384, vec.size)

        var sumSq = 0f
        for (x in vec) sumSq += x * x
        val norm = sqrt(sumSq)
        assertEquals(1.0f, norm, 1e-4f)
    }

    @Test
    fun testDynamicBatching() = runBlocking {
        val generator = LiteRtEmbeddingGenerator(FakeContext())
        val texts = (1..25).map { "Document chunk content number $it" }

        val results = generator.generateEmbeddings(texts, maxBatchSize = 16)
        assertEquals(25, results.size)

        for (vec in results) {
            assertEquals(384, vec.size)
            var sumSq = 0f
            for (x in vec) sumSq += x * x
            val norm = sqrt(sumSq)
            assertEquals(1.0f, norm, 1e-4f)
        }
    }

    @Test
    fun testEmptyAndBlankInputs() = runBlocking {
        val generator = LiteRtEmbeddingGenerator(FakeContext())
        val emptyListResult = generator.generateEmbeddings(emptyList())
        assertTrue(emptyListResult.isEmpty())

        val blankResult = generator.generateEmbedding("   ")
        assertEquals(384, blankResult.size)
    }
}
