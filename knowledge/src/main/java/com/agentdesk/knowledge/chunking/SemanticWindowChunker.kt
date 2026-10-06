package com.agentdesk.knowledge.chunking

/**
 * SemanticWindowChunker partitions document text into overlapping windows
 * with structured contextual breadcrumbs (document title, section, offsets).
 *
 * Requirements:
 * - Window size: 256–384 tokens (~1000–1500 chars).
 * - Overlap: ~50 tokens (~200 chars).
 * - Boundary awareness: avoids splitting in the middle of sentences or paragraphs.
 * - Breadcrumb injection: prepends [Doc: <Title> | Chunk: <Index>/<Total>] to provide
 *   self-contained semantic context during dense retrieval.
 */
class SemanticWindowChunker(
    private val targetWindowChars: Int = 1200, // ~300 tokens
    private val overlapChars: Int = 200        // ~50 tokens
) {

    data class Chunk(
        val index: Int,
        val content: String,
        val charOffset: Int,
        val pageNumber: Int = 1
    )

    /**
     * Splits [fullText] into semantic chunks with prepended breadcrumbs.
     */
    fun chunk(title: String, fullText: String, pageNumber: Int = 1): List<Chunk> {
        val trimmed = fullText.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.length <= targetWindowChars) {
            val breadcrumb = "[Doc: $title | Chunk: 1/1]\n"
            return listOf(
                Chunk(
                    index = 0,
                    content = breadcrumb + trimmed,
                    charOffset = 0,
                    pageNumber = pageNumber
                )
            )
        }

        val rawChunks = mutableListOf<Pair<Int, String>>()
        var cursor = 0
        val textLength = trimmed.length

        while (cursor < textLength) {
            val windowEnd = (cursor + targetWindowChars).coerceAtMost(textLength)
            var actualEnd = windowEnd

            // If we are not at EOF, seek the nearest sentence/paragraph boundary backward
            if (actualEnd < textLength) {
                val boundary = findNaturalBoundary(trimmed, cursor, actualEnd)
                if (boundary > cursor + overlapChars) {
                    actualEnd = boundary
                }
            }

            val slice = trimmed.substring(cursor, actualEnd).trim()
            if (slice.isNotEmpty()) {
                rawChunks.add(cursor to slice)
            }

            if (actualEnd >= textLength) break

            // Advance cursor with overlap
            val nextCursor = (actualEnd - overlapChars).coerceAtLeast(cursor + 1)
            cursor = nextCursor
        }

        val totalChunks = rawChunks.size
        return rawChunks.mapIndexed { idx, (offset, content) ->
            val breadcrumb = "[Doc: $title | Chunk: ${idx + 1}/$totalChunks]\n"
            Chunk(
                index = idx,
                content = breadcrumb + content,
                charOffset = offset,
                pageNumber = pageNumber
            )
        }
    }

    private fun findNaturalBoundary(text: String, start: Int, end: Int): Int {
        // Search backward from end for paragraph breaks "\n\n"
        val paragraphBreak = text.lastIndexOf("\n\n", end)
        if (paragraphBreak != -1 && paragraphBreak >= start + overlapChars) {
            return paragraphBreak + 2
        }

        // Search backward for line breaks "\n"
        val lineBreak = text.lastIndexOf('\n', end)
        if (lineBreak != -1 && lineBreak >= start + overlapChars) {
            return lineBreak + 1
        }

        // Search backward for sentence terminators (". ", "? ", "! ")
        val sentenceRegex = Regex("""[.!?]\s+""")
        val sub = text.substring(start, end)
        val match = sentenceRegex.findAll(sub).lastOrNull()
        if (match != null) {
            val matchEnd = start + match.range.last + 1
            if (matchEnd >= start + overlapChars) {
                return matchEnd
            }
        }

        // Search backward for whitespace
        val lastSpace = text.lastIndexOf(' ', end)
        if (lastSpace != -1 && lastSpace >= start + overlapChars) {
            return lastSpace + 1
        }

        return end
    }
}
