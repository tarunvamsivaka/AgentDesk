package com.agentdesk.knowledge.parser

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Extracts plain text from Microsoft Word (.docx) files with strict security hardening:
 * 1. ZIP-Bomb Protection:
 *    - Max compressed stream size: 25 MB
 *    - Max uncompressed total bytes: 50 MB
 *    - Max compression ratio: 100:1 (once decompressed size > 1 MB)
 *    - Max ZIP entries: 1,000 entries
 * 2. XXE Prevention:
 *    - Disallows doctype declarations (disallow-doctype-decl)
 *    - Disables external general and parameter entities
 *    - Disables loading external DTDs
 * 3. Character limit: 500,000 chars max
 */
object DocxTextExtractor {

    const val MAX_COMPRESSED_BYTES = 25L * 1024L * 1024L // 25 MB
    const val MAX_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L // 50 MB
    const val MAX_COMPRESSION_RATIO = 100L // 100:1
    const val MAX_ZIP_ENTRIES = 1_000
    const val MAX_CHARS = DocxSaxHandler.MAX_CHARS

    /**
     * Extracts all text paragraphs from the given .docx input stream.
     * Returns null if the stream is invalid, exceeds security bounds, or does not contain word/document.xml.
     */
    fun extractText(inputStream: InputStream): String? {
        return try {
            val countingCompressedStream = CountingInputStream(inputStream, MAX_COMPRESSED_BYTES)
            var totalUncompressedBytes = 0L
            var entryCount = 0

            ZipInputStream(countingCompressedStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        // Exceeded maximum ZIP entry threshold -> potential archive bomb
                        return null
                    }

                    if (entry.name == "word/document.xml") {
                        val factory = SAXParserFactory.newInstance()
                        factory.isNamespaceAware = true

                        // Security: Hardening XML parser against XXE and Billion Laughs
                        try {
                            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        } catch (_: Exception) {}
                        try {
                            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                        } catch (_: Exception) {}
                        try {
                            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        } catch (_: Exception) {}
                        try {
                            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                        } catch (_: Exception) {}

                        val parser = factory.newSAXParser()
                        val handler = DocxSaxHandler()

                        // Monitor uncompressed bytes read from entry
                        val entryStream = object : FilterInputStream(NonClosingInputStream(zip)) {
                            override fun read(): Int {
                                val b = super.read()
                                if (b != -1) {
                                    trackUncompressed(1L)
                                }
                                return b
                            }

                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                val bytesRead = super.read(b, off, len)
                                if (bytesRead > 0) {
                                    trackUncompressed(bytesRead.toLong())
                                }
                                return bytesRead
                            }

                            private fun trackUncompressed(bytes: Long) {
                                totalUncompressedBytes += bytes
                                if (totalUncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                                    throw SecurityException("Exceeded maximum uncompressed size: $totalUncompressedBytes > $MAX_UNCOMPRESSED_BYTES")
                                }
                                val compressedRead = countingCompressedStream.bytesRead
                                if (totalUncompressedBytes > 1024 * 1024 && compressedRead > 0) {
                                    val ratio = totalUncompressedBytes / maxOf(1L, compressedRead)
                                    if (ratio > MAX_COMPRESSION_RATIO) {
                                        throw SecurityException("ZIP-bomb detected: compression ratio $ratio:1 exceeds $MAX_COMPRESSION_RATIO:1 limit")
                                    }
                                }
                            }
                        }

                        try {
                            parser.parse(entryStream, handler)
                        } catch (_: CharacterLimitExceededException) {
                            // Reached MAX_CHARS limit, proceed with extracted text
                        } catch (e: SecurityException) {
                            return null
                        } catch (e: Exception) {
                            // Catch XXE SAXParseException or malformed XML
                            return null
                        }
                        return handler.getExtractedText()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private class CharacterLimitExceededException : org.xml.sax.SAXException("Character limit exceeded")

    internal class DocxSaxHandler : DefaultHandler() {
        private val textBuilder = StringBuilder()
        private var inTextElement = false

        companion object {
            const val MAX_CHARS = 500_000
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val tag = localName?.ifEmpty { qName } ?: qName ?: ""
            if (tag.equals("t", ignoreCase = true) || tag.endsWith(":t")) {
                inTextElement = true
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inTextElement && ch != null) {
                val remaining = MAX_CHARS - textBuilder.length
                if (remaining > 0) {
                    val toAppend = minOf(length, remaining)
                    textBuilder.append(ch, start, toAppend)
                }
                if (textBuilder.length >= MAX_CHARS) {
                    throw CharacterLimitExceededException()
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = localName?.ifEmpty { qName } ?: qName ?: ""
            if (tag.equals("t", ignoreCase = true) || tag.endsWith(":t")) {
                inTextElement = false
            } else if (tag.equals("p", ignoreCase = true) || tag.endsWith(":p")) {
                if (textBuilder.length < MAX_CHARS) {
                    textBuilder.append("\n")
                }
            }
        }

        fun getExtractedText(): String = textBuilder.toString().trim()
    }

    /**
     * Prevents the XML parser from closing the underlying ZipInputStream when
     * reaching the end of the entry stream.
     */
    private class NonClosingInputStream(inputStream: InputStream) : FilterInputStream(inputStream) {
        override fun close() {
            // No-op: keep ZipInputStream open for subsequent entries if needed
        }
    }

    /**
     * Tracks the number of compressed bytes read and enforces a strict maximum threshold.
     */
    private class CountingInputStream(
        inputStream: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(inputStream) {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            if (bytesRead >= maxBytes) {
                throw SecurityException("Exceeded maximum compressed stream size: $maxBytes bytes")
            }
            val b = super.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= maxBytes) {
                throw SecurityException("Exceeded maximum compressed stream size: $maxBytes bytes")
            }
            val toRead = minOf(len.toLong(), maxBytes - bytesRead).toInt()
            val count = super.read(b, off, toRead)
            if (count > 0) bytesRead += count
            return count
        }
    }
}
