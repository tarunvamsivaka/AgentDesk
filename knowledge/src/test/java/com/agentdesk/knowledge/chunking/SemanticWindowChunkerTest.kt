package com.agentdesk.knowledge.chunking

import org.junit.Assert.*
import org.junit.Test

class SemanticWindowChunkerTest {

    private val chunker = SemanticWindowChunker(
        targetWindowChars = 200,
        overlapChars = 40
    )

    @Test
    fun chunk_emptyStringReturnsEmptyList() {
        val result = chunker.chunk("EmptyDoc", "")
        assertTrue(result.isEmpty())
    }

    @Test
    fun chunk_shortStringReturnsSingleChunkWithBreadcrumb() {
        val text = "This is a brief note about passport renewal."
        val chunks = chunker.chunk("Passport Notes", text)

        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertTrue(chunks[0].content.contains("[Doc: Passport Notes | Chunk: 1/1]"))
        assertTrue(chunks[0].content.contains(text))
    }

    @Test
    fun chunk_longDocumentSplitsIntoOverlappingWindowsWithBreadcrumbs() {
        val paragraph1 = "AgentDesk is an adaptive, privacy-first, on-device AI copilot for Android. It operates under strict zero cloud egress guarantees."
        val paragraph2 = "The local vector engine executes pure Kotlin IEEE-754 vector arithmetic without dynamic C++ NDK library dependencies."
        val paragraph3 = "Reciprocal Rank Fusion fuses FTS4 sparse search and dense embeddings into unified semantic rankings with low latency."
        val fullText = "$paragraph1\n\n$paragraph2\n\n$paragraph3"

        val chunks = chunker.chunk("Architecture Spec", fullText)

        assertTrue(chunks.size >= 2)
        // Verify monotonicity and breadcrumbs
        chunks.forEachIndexed { i, c ->
            assertEquals(i, c.index)
            assertTrue(c.content.startsWith("[Doc: Architecture Spec | Chunk: ${i + 1}/${chunks.size}]"))
        }

        // Verify natural boundary breaks or overlap retention
        assertTrue(chunks[0].content.contains("AgentDesk"))
    }
}
