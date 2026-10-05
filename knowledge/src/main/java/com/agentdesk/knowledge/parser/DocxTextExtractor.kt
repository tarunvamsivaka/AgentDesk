package com.agentdesk.knowledge.parser

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Extracts plain text from Microsoft Word (.docx) files.
 *
 * A .docx file is an Open Packaging Conventions (OPC) ZIP archive containing
 * the main document body inside "word/document.xml".
 *
 * This extractor streams the ZIP archive without uncompressing it to disk,
 * and parses the XML using standard Android/Java SAX parsers.
 * Zero external dependencies, zero network egress, fully on-device.
 */
object DocxTextExtractor {

    const val MAX_CHARS = DocxSaxHandler.MAX_CHARS

    /**
     * Extracts all text paragraphs from the given .docx input stream.
     * Returns null if the stream is invalid or does not contain word/document.xml.
     */
    fun extractText(inputStream: InputStream): String? {
        return try {
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val factory = SAXParserFactory.newInstance()
                        factory.isNamespaceAware = true
                        val parser = factory.newSAXParser()
                        val handler = DocxSaxHandler()
                        // Security: disallow doctype declarations to prevent XXE injection
                        try {
                            parser.xmlReader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        } catch (_: Exception) {
                            // Non-fatal if parser does not support this specific feature flag
                        }
                        try {
                            parser.parse(NonClosingInputStream(zip), handler)
                        } catch (_: CharacterLimitExceededException) {
                            // Reached MAX_CHARS limit, proceed with extracted text
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
}
