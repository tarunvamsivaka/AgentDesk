package com.agentdesk.knowledge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MarkdownTextExtractorTest {

    @Test
    fun testPreservesHeadingsAndCodeBlocks() {
        val markdown = """
            # AgentDesk System Architecture
            
            This is an overview of the privacy-first copilot.
            
            ## Policy Engine Invariants
            - Zero cloud egress
            - Cryptographic receipts
            
            ```kotlin
            fun evaluate(command: Command): CommandResult {
                return CommandResult.Accepted(command)
            }
            ```
            
            ### Final Notes
            Everything runs on-device.
        """.trimIndent()

        val extracted = MarkdownTextExtractor.extractText(ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8)))
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("# AgentDesk System Architecture"))
        assertTrue(extracted.contains("## Policy Engine Invariants"))
        assertTrue(extracted.contains("```kotlin"))
        assertTrue(extracted.contains("### Final Notes"))
        assertTrue(extracted.contains("return CommandResult.Accepted(command)"))
    }

    @Test
    fun testStripsHtmlAndNormalizesLinksAndImages() {
        val markdown = """
            # Welcome <span style="color:red">User</span>
            
            <p>Visit our website at <a href="https://agentdesk.ai">docs</a></p>
            
            Here is a diagram: ![Architecture Diagram](https://agentdesk.ai/arch.png)
            
            Read the [spec](https://agentdesk.ai/spec.pdf) for details.
        """.trimIndent()

        val extracted = MarkdownTextExtractor.extractText(ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8)))
        assertNotNull(extracted)
        assertTrue(!extracted!!.contains("<span"))
        assertTrue(!extracted.contains("</span>"))
        assertTrue(!extracted.contains("<p>"))
        assertTrue(!extracted.contains("</p>"))
        assertTrue(extracted.contains("[Image: Architecture Diagram]"))
        assertTrue(extracted.contains("spec (https://agentdesk.ai/spec.pdf)"))
    }

    @Test
    fun testRejectsOversizedStream() {
        val oversizedStream = object : InputStream() {
            private var count = 0L
            override fun read(): Int {
                count++
                return if (count > MarkdownTextExtractor.MAX_STREAM_BYTES + 100) -1 else 'A'.code
            }
        }
        val extracted = MarkdownTextExtractor.extractText(oversizedStream)
        assertNull("Oversized stream > 25MB must be rejected", extracted)
    }

    @Test
    fun testEmptyInputReturnsEmpty() {
        val extracted = MarkdownTextExtractor.extractText(ByteArrayInputStream("   ".toByteArray()))
        assertNotNull(extracted)
        assertEquals("", extracted)
    }
}
