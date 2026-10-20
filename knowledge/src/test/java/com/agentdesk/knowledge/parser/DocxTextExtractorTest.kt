package com.agentdesk.knowledge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
        val largeBlock = "A".repeat(100_000)
        val xmlContent = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            for (i in 1..6) {
                append("<w:p><w:r><w:t>$largeBlock</w:t></w:r></w:p>")
            }
            append("""</w:body></w:document>""")
        }

        val docxBytes = createMockDocx(mapOf("word/document.xml" to xmlContent))
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(docxBytes))

        assertNotNull(extracted)
        assertTrue(
            "Extracted text should not exceed MAX_CHARS (500,000), got ${extracted?.length}",
            (extracted?.length ?: 0) <= DocxTextExtractor.MAX_CHARS
        )
        assertEquals(DocxTextExtractor.MAX_CHARS, extracted?.length)
    }

    @Test
    fun testRejectXxePayload() {
        val xxeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE test [
                <!ENTITY xxe SYSTEM "file:///data/data/com.agentdesk/databases/agentdesk.db">
            ]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p><w:r><w:t>&xxe;</w:t></w:r></w:p>
                </w:body>
            </w:document>
        """.trimIndent()

        val docxBytes = createMockDocx(mapOf("word/document.xml" to xxeXml))
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(docxBytes))
        // XXE with <!DOCTYPE must be blocked and return null
        assertNull("XXE payload with DOCTYPE must be rejected", extracted)
    }

    @Test
    fun testRejectExcessiveZipEntries() {
        // Create an archive with > 1,000 entries
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for (i in 1..1005) {
                val entry = ZipEntry("file_$i.txt")
                zos.putNextEntry(entry)
                zos.write("content".toByteArray())
                zos.closeEntry()
            }
        }
        val bytes = baos.toByteArray()
        val extracted = DocxTextExtractor.extractText(ByteArrayInputStream(bytes))
        assertNull("Archives with > 1,000 entries must be rejected as potential bombs", extracted)
    }

    @Test
    fun testRejectStreamExceedingMaxCompressedBytes() {
        // Stream that reports > 25 MB
        val fakeOversizedStream = object : InputStream() {
            private var count = 0L
            override fun read(): Int {
                count++
                return if (count > DocxTextExtractor.MAX_COMPRESSED_BYTES + 10) -1 else 0x50
            }
        }
        val extracted = DocxTextExtractor.extractText(fakeOversizedStream)
        assertNull("Stream exceeding 25 MB compressed limit must be rejected", extracted)
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
