package com.agentdesk.core.persistence.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkEntityTest {

    @Test
    fun `two distinct TextChunkEntity instances with identical byte arrays evaluate as equal and have same hashCode`() {
        val bytes1 = byteArrayOf(1, 2, -128, 127, 0, 42)
        val bytes2 = byteArrayOf(1, 2, -128, 127, 0, 42) // Distinct instance, identical bytes

        val entity1 = TextChunkEntity(
            id = 10L,
            documentId = 1L,
            chunkIndex = 0,
            content = "Offline on-device local vector retrieval test",
            charOffset = 0,
            pageNumber = 1,
            createdAt = 1000L,
            quantizedEmbedding = bytes1,
            quantizationScale = 0.05f,
            vectorNorm = 1.25f
        )

        val entity2 = TextChunkEntity(
            id = 10L,
            documentId = 1L,
            chunkIndex = 0,
            content = "Offline on-device local vector retrieval test",
            charOffset = 0,
            pageNumber = 1,
            createdAt = 1000L,
            quantizedEmbedding = bytes2,
            quantizationScale = 0.05f,
            vectorNorm = 1.25f
        )

        // Verify value-based equality instead of reference equality
        assertTrue(entity1.quantizedEmbedding !== entity2.quantizedEmbedding)
        assertEquals(entity1, entity2)
        assertEquals(entity1.hashCode(), entity2.hashCode())
    }

    @Test
    fun `different quantizedEmbedding bytes evaluate as not equal`() {
        val bytes1 = byteArrayOf(1, 2, 3)
        val bytes2 = byteArrayOf(1, 2, 4)

        val entity1 = TextChunkEntity(
            id = 10L,
            documentId = 1L,
            chunkIndex = 0,
            content = "Test content",
            quantizedEmbedding = bytes1
        )

        val entity2 = TextChunkEntity(
            id = 10L,
            documentId = 1L,
            chunkIndex = 0,
            content = "Test content",
            quantizedEmbedding = bytes2
        )

        assertNotEquals(entity1, entity2)
        assertNotEquals(entity1.hashCode(), entity2.hashCode())
    }

    @Test
    fun `EmbeddingVectorEntity value-based equality on vectorBlob`() {
        val blob1 = byteArrayOf(10, 20, 30)
        val blob2 = byteArrayOf(10, 20, 30)

        val vec1 = EmbeddingVectorEntity(
            id = 5L,
            chunkId = 10L,
            vectorBlob = blob1,
            modelVersion = "v1",
            createdAt = 5000L
        )

        val vec2 = EmbeddingVectorEntity(
            id = 5L,
            chunkId = 10L,
            vectorBlob = blob2,
            modelVersion = "v1",
            createdAt = 5000L
        )

        assertTrue(vec1.vectorBlob !== vec2.vectorBlob)
        assertEquals(vec1, vec2)
        assertEquals(vec1.hashCode(), vec2.hashCode())
    }
}
