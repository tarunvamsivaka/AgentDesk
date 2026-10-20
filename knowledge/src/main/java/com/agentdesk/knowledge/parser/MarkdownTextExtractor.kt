package com.agentdesk.knowledge.parser

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Extracts and normalizes text from Markdown (.md) documents.
 *
 * Preserves structural hierarchy:
 * - Headings (#, ##, ###, etc.) remain intact for SemanticWindowChunker section detection.
 * - Fenced code blocks (``` ... ```) and indented code are preserved.
 * - Strips raw HTML tags and excessive empty whitespace.
 *
 * Security: Enforces a maximum stream size (25 MB) and maximum character extraction limit (500,000 chars).
 */
object MarkdownTextExtractor {

    const val MAX_STREAM_BYTES = 25L * 1024L * 1024L // 25 MB
    const val MAX_CHARS = 500_000

    private val HTML_TAG_REGEX = Regex("""<[^>]*>""")
    private val MULTI_BLANK_LINES_REGEX = Regex("""\n{3,}""")
    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

    /**
     * Extracts and normalizes text from the provided InputStream.
     * Returns null if the stream exceeds the size limit or cannot be parsed.
     */
    fun extractText(inputStream: InputStream): String? {
        return try {
            val countingStream = object : FilterInputStream(inputStream) {
                var bytesRead = 0L

                override fun read(): Int {
                    val b = super.read()
                    if (b != -1) {
                        bytesRead++
                        if (bytesRead > MAX_STREAM_BYTES) {
                            throw IOException("Stream exceeded max limit of 25MB")
                        }
                    }
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = super.read(b, off, len)
                    if (count != -1) {
                        bytesRead += count
                        if (bytesRead > MAX_STREAM_BYTES) {
                            throw IOException("Stream exceeded max limit of 25MB")
                        }
                    }
                    return count
                }
            }

            val reader = InputStreamReader(countingStream, Charsets.UTF_8)
            val buffer = CharArray(8192)
            val rawBuilder = StringBuilder()

            var read = reader.read(buffer)
            while (read != -1) {
                if (rawBuilder.length < MAX_CHARS * 2) {
                    val charsToAppend = minOf(read, (MAX_CHARS * 2) - rawBuilder.length)
                    rawBuilder.append(buffer, 0, charsToAppend)
                }
                read = reader.read(buffer)
            }

            val rawMarkdown = rawBuilder.toString()
            normalizeMarkdown(rawMarkdown)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalizes Markdown content:
     * - Preserves section headers (# Title)
     * - Preserves fenced code blocks (```)
     * - Strips HTML tags (<div class="..."> -> "")
     * - Normalizes image tags to [Image: alt text]
     * - Normalizes link tags to link text (url)
     * - Truncates to MAX_CHARS
     */
    fun normalizeMarkdown(rawContent: String): String {
        if (rawContent.isBlank()) return ""

        // 1. Normalize images: ![Alt text](url) -> [Image: Alt text]
        var processed = MARKDOWN_IMAGE_REGEX.replace(rawContent) { match ->
            val alt = match.groupValues[1].trim()
            if (alt.isNotEmpty()) "[Image: $alt]" else ""
        }

        // 2. Normalize links: [Link text](url) -> Link text (url)
        processed = MARKDOWN_LINK_REGEX.replace(processed) { match ->
            val text = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            "$text ($url)"
        }

        // 3. Strip raw HTML tags
        processed = HTML_TAG_REGEX.replace(processed, "")

        // 4. Collapse multiple blank lines (> 2 newlines -> 2 newlines)
        processed = MULTI_BLANK_LINES_REGEX.replace(processed, "\n\n")

        val trimmed = processed.trim()
        return if (trimmed.length > MAX_CHARS) {
            trimmed.substring(0, MAX_CHARS)
        } else {
            trimmed
        }
    }
}
