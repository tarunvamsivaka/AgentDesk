package com.agentdesk.knowledge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxTextExtractorTest {

    @Test
    fun testExtractTextFromValidDocx() {
        val xmlContent = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:r><w:t>AgentDesk Architecture Plan</w:t></w:r>
                    </w:p>
                    <w:p>
                        <w:r><w:t>Privacy-first on-device AI copilot for Android.</w:t></w:r>
                    </w:p>
                </w:body>
            </w:document>
        """.trimIndent()

        val docxBytes = createMockDocx(mapOf("word/document.xml" to xmlContent))
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(docxBytes))

        assertNotNull(extracted)
        assertEquals(
            "AgentDesk Architecture Plan\nPrivacy-first on-device AI copilot for Android.",
            extracted
        )
    }

    @Test
    fun testExtractTextNonDocxZipReturnsNull() {
        val docxBytes = createMockDocx(mapOf("other_file.txt" to "Hello World"))
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(docxBytes))
        assertNull(extracted)
    }

    @Test
    fun testCorruptedStreamReturnsNull() {
        val invalidBytes = "not a zip file at all".toByteArray()
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(invalidBytes))
        assertNull(extracted)
    }

    @Test
    fun testExtractTextEnforcesCharacterLimit() {
        // Construct a docx XML that exceeds the MAX_CHARS threshold
        val largeBlock = "A".repeat(100_000)
        val xmlContent = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            for (i in 1..6) { // 6 * 100_000 = 600,000 characters (> 500,000)
                append("<w:p><w:r><w:t>$largeBlock</w:t></w:r></w:p>")
            }
            append("""</w:body></w:document>""")
        }

        val docxBytes = createMockDocx(mapOf("word/document.xml" to xmlContent))
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(docxBytes))

        assertNotNull(extracted)
        org.junit.Assert.assertTrue(
            "Extracted text should not exceed MAX_CHARS (500,000), got ${extracted?.length}",
            (extracted?.length ?: 0) <= DocxTextExtractor.MAX_CHARS
        )
        assertEquals(DocxTextExtractor.MAX_CHARS, extracted?.length)
    }

    private fun createMockDocx(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
