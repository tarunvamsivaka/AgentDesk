package com.agentdesk.knowledge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream

class PdfTextExtractorTest {

    @Test
    fun testExtractTextFromValidUncompressedPdf() {
        val pdfContent = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
            append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n")
            append("4 0 obj\n<< /Length 50 >>\nstream\n")
            append("BT\n/F1 12 Tf\n(AgentDesk On-Device PDF) Tj\nET\n")
            append("endstream\nendobj\n")
            append("trailer\n<< /Root 1 0 R >>\n%%EOF")
        }

        val extracted = PdfTextExtractor.extractText(
            ByteArrayInputStream(pdfContent.toByteArray(StandardCharsets.ISO_8859_1))
        )
        assertNotNull(extracted)
        assertTrue(
            "Extracted text should contain 'AgentDesk On-Device PDF', got: $extracted",
            extracted!!.contains("AgentDesk On-Device PDF")
        )
    }

    @Test
    fun testExtractTextWithArrayTjAndHexOperators() {
        val streamContent = "BT /F1 12 Tf [(Hello) 20 (World)] TJ <4167656E74> Tj ET"
        val extracted = PdfTextExtractor.extractTextFromPdfStream(streamContent)
        assertTrue(extracted.contains("HelloWorld"))
        assertTrue(extracted.contains("Agent"))
    }

    @Test
    fun testExtractTextFromFlateCompressedPdf() {
        val rawStream = "BT /F1 14 Tf (Confidential Local Document) Tj ET".toByteArray(StandardCharsets.ISO_8859_1)
        val deflated = deflate(rawStream)

        val header = "%PDF-1.5\n1 0 obj\n<< /Type /Page >>\nendobj\n2 0 obj\n<< /Filter /FlateDecode /Length ${deflated.size} >>\nstream\n"
        val footer = "\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF"

        val baos = ByteArrayOutputStream()
        baos.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        baos.write(deflated)
        baos.write(footer.toByteArray(StandardCharsets.ISO_8859_1))

        val extracted = PdfTextExtractor.extractText(ByteArrayInputStream(baos.toByteArray()))
        assertNotNull(extracted)
        assertTrue(
            "Extracted text should decompress Flate stream, got: $extracted",
            extracted!!.contains("Confidential Local Document")
        )
    }

    @Test
    fun testRejectNonPdfFile() {
        val nonPdf = "This is definitely not a PDF document.".toByteArray(Charsets.UTF_8)
        val extracted = PdfTextExtractor.extractText(ByteArrayInputStream(nonPdf))
        assertNull("Non-PDF stream must be rejected", extracted)
    }

    @Test
    fun testRejectOversizedStream() {
        val fakeOversizedStream = object : InputStream() {
            private var count = 0L
            private val magic = "%PDF-".toByteArray()
            override fun read(): Int {
                count++
                if (count <= 5) return magic[(count - 1).toInt()].toInt()
                return if (count > PdfTextExtractor.MAX_STREAM_BYTES + 10) -1 else 0x20
            }
        }
        val extracted = PdfTextExtractor.extractText(fakeOversizedStream)
        assertNull("Streams exceeding 25 MB must be rejected", extracted)
    }

    @Test
    fun testRejectExceedingPageCap() {
        // Construct PDF with > 500 pages
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        for (i in 1..505) {
            sb.append("$i 0 obj\n<< /Type /Page >>\nendobj\n")
        }
        sb.append("trailer << >>\n%%EOF")

        val extracted = PdfTextExtractor.extractText(ByteArrayInputStream(sb.toString().toByteArray()))
        assertNull("PDF exceeding 500-page cap must be rejected", extracted)
    }

    @Test
    fun testScannedPdfExtractsFallbackDescriptorAndMetadata() {
        val scannedPdf = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
            append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /XObject << /Im1 4 0 R >> >> >>\nendobj\n")
            append("4 0 obj\n<< /Type /XObject /Subtype /Image /Width 100 /Height 100 >>\nstream\n[binary image data]\nendstream\nendobj\n")
            append("5 0 obj\n<< /Title (Scanned Property Tax Receipt) /Author (AgentDesk Scanner) >>\nendobj\n")
            append("trailer\n<< /Root 1 0 R /Info 5 0 R >>\n%%EOF")
        }

        val extracted = PdfTextExtractor.extractText(
            ByteArrayInputStream(scannedPdf.toByteArray(StandardCharsets.ISO_8859_1))
        )
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("[Scanned / Image-based PDF Document - 1 page(s)]"))
        assertTrue(extracted.contains("Title: Scanned Property Tax Receipt"))
        assertTrue(extracted.contains("Author: AgentDesk Scanner"))
    }

    @Test
    fun testScannedPdfWithoutMetadataProducesCleanPageDescriptor() {
        val imageOnlyPdf = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            append("2 0 obj\n<< /Type /Pages /Kids [3 0 R, 4 0 R] /Count 2 >>\nendobj\n")
            append("3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n")
            append("4 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n")
            append("trailer\n<< /Root 1 0 R >>\n%%EOF")
        }

        val extracted = PdfTextExtractor.extractText(
            ByteArrayInputStream(imageOnlyPdf.toByteArray(StandardCharsets.ISO_8859_1))
        )
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("[Scanned / Image-based PDF Document - 2 page(s)]"))
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        DeflaterOutputStream(baos).use { dos ->
            dos.write(bytes)
        }
        return baos.toByteArray()
    }
}
