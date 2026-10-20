package com.agentdesk.knowledge.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/**
 * Zero-network, pure-JVM streaming PDF text extractor compatible with Android minSdk 29+.
 *
 * Security & Resource Invariants:
 * - Strict magic-bytes verification: Input stream must begin with "%PDF-".
 * - Stream size limit: Maximum 25 MB (26,214,400 bytes).
 * - Page cap: Maximum 500 pages.
 * - Character extraction cap: 500,000 characters.
 * - Zero external native dependencies, runs purely in JVM RAM buffers.
 */
object PdfTextExtractor {

    const val MAX_STREAM_BYTES = 25L * 1024L * 1024L // 25 MB
    const val MAX_PAGES = 500
    const val MAX_CHARS = 500_000

    private val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Extracts plain text from the given PDF input stream.
     * Returns null if stream is invalid, exceeds 25 MB, or is not a valid PDF.
     */
    fun extractText(inputStream: InputStream): String? {
        return try {
            val countingStream = BoundedInputStream(inputStream, MAX_STREAM_BYTES)

            // 1. Verify magic bytes "%PDF-"
            val header = ByteArray(5)
            val readHeader = countingStream.read(header)
            if (readHeader < 5 || !header.contentEquals(PDF_MAGIC)) {
                return null
            }

            // 2. Read remaining stream into memory buffer up to MAX_STREAM_BYTES
            val buffer = ByteArrayOutputStream()
            buffer.write(header)
            val temp = ByteArray(8192)
            var n: Int
            while (countingStream.read(temp).also { n = it } != -1) {
                buffer.write(temp, 0, n)
            }
            val pdfBytes = buffer.toByteArray()

            // 3. Count pages
            val pageCount = countPages(pdfBytes)
            if (pageCount > MAX_PAGES) {
                // Exceeds max page cap -> reject or cap
                // If strict rejection required for security bounds:
                return null
            }

            // 4. Extract text from content streams
            val extracted = extractStreamsText(pdfBytes)
            if (extracted.isBlank()) {
                null
            } else if (extracted.length > MAX_CHARS) {
                extracted.substring(0, MAX_CHARS).trim()
            } else {
                extracted.trim()
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Estimates page count by searching for "/Type /Page" occurrences (excluding "/Pages").
     */
    internal fun countPages(pdfBytes: ByteArray): Int {
        val content = String(pdfBytes, StandardCharsets.ISO_8859_1)
        val pageRegex = Regex("""/Type\s*/Page\b(?!\s*s)""")
        val matches = pageRegex.findAll(content).count()
        return if (matches == 0) 1 else matches
    }

    /**
     * Parses all stream ... endstream blocks, decompressing FlateDecode if present,
     * and extracts text operators.
     */
    private fun extractStreamsText(pdfBytes: ByteArray): String {
        val result = StringBuilder()
        val text = String(pdfBytes, StandardCharsets.ISO_8859_1)

        var searchIndex = 0
        while (searchIndex < text.length && result.length < MAX_CHARS) {
            val streamStartKeyword = text.indexOf("stream", searchIndex)
            if (streamStartKeyword == -1) break

            // Locate the dictionary preceding the stream
            val dictStart = text.lastIndexOf("<<", streamStartKeyword)
            val isFlate = if (dictStart in searchIndex until streamStartKeyword) {
                val dict = text.substring(dictStart, streamStartKeyword)
                dict.contains("/FlateDecode") || dict.contains("/Fl")
            } else {
                false
            }

            // Stream data begins after "stream\r\n" or "stream\n"
            var dataStart = streamStartKeyword + 6
            if (dataStart < text.length && text[dataStart] == '\r') dataStart++
            if (dataStart < text.length && text[dataStart] == '\n') dataStart++

            val streamEndKeyword = text.indexOf("endstream", dataStart)
            if (streamEndKeyword == -1) break

            var dataEnd = streamEndKeyword
            if (dataEnd > dataStart && text[dataEnd - 1] == '\n') dataEnd--
            if (dataEnd > dataStart && text[dataEnd - 1] == '\r') dataEnd--

            if (dataEnd > dataStart) {
                val rawStreamBytes = pdfBytes.copyOfRange(dataStart, dataEnd)
                val decompressedBytes = if (isFlate) {
                    decompressFlate(rawStreamBytes)
                } else {
                    rawStreamBytes
                }

                if (decompressedBytes != null) {
                    val streamContent = String(decompressedBytes, StandardCharsets.ISO_8859_1)
                    val streamText = extractTextFromPdfStream(streamContent)
                    if (streamText.isNotBlank()) {
                        if (result.isNotEmpty()) result.append("\n")
                        result.append(streamText)
                    }
                }
            }

            searchIndex = streamEndKeyword + 9
        }

        // If no streams contained text (e.g., uncompressed single-object PDF), scan top-level BT..ET
        if (result.isBlank()) {
            val directText = extractTextFromPdfStream(text)
            if (directText.isNotBlank()) {
                result.append(directText)
            }
        }

        return result.toString()
    }

    private fun decompressFlate(compressed: ByteArray): ByteArray? {
        return try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var len: Int
                var total = 0
                while (inflater.read(buf).also { len = it } != -1) {
                    total += len
                    if (total > MAX_STREAM_BYTES) return null
                    out.write(buf, 0, len)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            // Could be raw or corrupt Flate stream
            null
        }
    }

    /**
     * Extracts text from PDF text operators between BT (Begin Text) and ET (End Text):
     * - (Text) Tj
     * - [(T) 20 (ext)] TJ
     * - ' (Text)
     * - " (Text)
     * - <Hex> Tj
     */
    internal fun extractTextFromPdfStream(content: String): String {
        val out = StringBuilder()
        var pos = 0
        while (pos < content.length && out.length < MAX_CHARS) {
            val bt = content.indexOf("BT", pos)
            if (bt == -1) break

            val et = content.indexOf("ET", bt)
            val chunk = if (et != -1) {
                pos = et + 2
                content.substring(bt + 2, et)
            } else {
                pos = content.length
                content.substring(bt + 2)
            }

            parseTextOperators(chunk, out)
        }
        return out.toString().trim()
    }

    private fun parseTextOperators(chunk: String, out: StringBuilder) {
        var i = 0
        while (i < chunk.length && out.length < MAX_CHARS) {
            val ch = chunk[i]
            if (ch == '(') {
                // Literal string: (...)
                val start = i + 1
                var depth = 1
                var j = start
                var escaped = false
                while (j < chunk.length && depth > 0) {
                    val c = chunk[j]
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '(') {
                        depth++
                    } else if (c == ')') {
                        depth--
                    }
                    j++
                }
                val rawStr = chunk.substring(start, if (depth == 0) j - 1 else j)
                val unescaped = unescapePdfString(rawStr)
                if (unescaped.isNotBlank()) {
                    if (out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) {
                        out.append(" ")
                    }
                    out.append(unescaped)
                }
                i = j
            } else if (ch == '[') {
                // Array string: [...] TJ
                val start = i + 1
                val end = chunk.indexOf(']', start)
                if (end != -1) {
                    val arrayContent = chunk.substring(start, end)
                    val arrayText = extractFromArray(arrayContent)
                    if (arrayText.isNotBlank()) {
                        if (out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) {
                            out.append(" ")
                        }
                        out.append(arrayText)
                    }
                    i = end + 1
                } else {
                    i++
                }
            } else if (ch == '<' && i + 1 < chunk.length && chunk[i + 1] != '<') {
                // Hex string: <...>
                val start = i + 1
                val end = chunk.indexOf('>', start)
                if (end != -1) {
                    val hex = chunk.substring(start, end).replace("\\s".toRegex(), "")
                    val decoded = decodeHex(hex)
                    if (decoded.isNotBlank()) {
                        if (out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) {
                            out.append(" ")
                        }
                        out.append(decoded)
                    }
                    i = end + 1
                } else {
                    i++
                }
            } else if (ch == 'T' && i + 1 < chunk.length && (chunk[i + 1] == '*' || chunk[i + 1] == 'd' || chunk[i + 1] == 'D')) {
                // New line operators (T*, Td, TD)
                if (out.isNotEmpty() && !out.endsWith("\n")) {
                    out.append("\n")
                }
                i += 2
            } else {
                i++
            }
        }
    }

    private fun extractFromArray(arrayStr: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < arrayStr.length) {
            val ch = arrayStr[i]
            if (ch == '(') {
                val start = i + 1
                var depth = 1
                var j = start
                var escaped = false
                while (j < arrayStr.length && depth > 0) {
                    val c = arrayStr[j]
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '(') {
                        depth++
                    } else if (c == ')') {
                        depth--
                    }
                    j++
                }
                val raw = arrayStr.substring(start, if (depth == 0) j - 1 else j)
                result.append(unescapePdfString(raw))
                i = j
            } else if (ch == '<' && i + 1 < arrayStr.length && arrayStr[i + 1] != '<') {
                val start = i + 1
                val end = arrayStr.indexOf('>', start)
                if (end != -1) {
                    val hex = arrayStr.substring(start, end).replace("\\s".toRegex(), "")
                    result.append(decodeHex(hex))
                    i = end + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return result.toString()
    }

    private fun decodeHex(hex: String): String {
        return try {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                val index = i * 2
                bytes[i] = hex.substring(index, index + 2).toInt(16).toByte()
            }
            String(bytes, StandardCharsets.UTF_8).replace("\u0000", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun unescapePdfString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(' -> { sb.append('('); i += 2 }
                    ')' -> { sb.append(')'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    in '0'..'7' -> {
                        // Octal sequence \ddd (1 to 3 digits)
                        var octalDigits = "" + next
                        var k = i + 2
                        while (k < s.length && k < i + 4 && s[k] in '0'..'7') {
                            octalDigits += s[k]
                            k++
                        }
                        try {
                            sb.append(octalDigits.toInt(8).toChar())
                        } catch (_: Exception) {
                            sb.append(next)
                        }
                        i = k
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private class BoundedInputStream(
        inputStream: InputStream,
        private val maxBytes: Long
    ) : java.io.FilterInputStream(inputStream) {
        private var bytesRead = 0L

        override fun read(): Int {
            if (bytesRead >= maxBytes) {
                throw SecurityException("Exceeded maximum PDF stream limit: $maxBytes bytes")
            }
            val b = super.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= maxBytes) {
                throw SecurityException("Exceeded maximum PDF stream limit: $maxBytes bytes")
            }
            val toRead = minOf(len.toLong(), maxBytes - bytesRead).toInt()
            val count = super.read(b, off, toRead)
            if (count > 0) bytesRead += count
            return count
        }
    }
}
