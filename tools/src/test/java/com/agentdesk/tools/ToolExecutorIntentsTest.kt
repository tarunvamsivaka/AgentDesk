package com.agentdesk.tools

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying transparent system Intent dispatch in [ToolExecutor]:
 * 1. draft_sms constructs Intent.ACTION_SENDTO with smsto: scheme (never SmsManager or silent background send).
 * 2. open_dialer constructs Intent.ACTION_DIAL with tel: scheme (never ACTION_CALL or silent calling).
 * 3. Verified by contract constants and strict AST / source pattern analysis on ToolExecutor.kt.
 */
class ToolExecutorIntentsTest {

    private fun findToolExecutorFile(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            val candidate = File(current, "tools/src/main/java/com/agentdesk/tools/executor/ToolExecutor.kt")
            if (candidate.exists()) {
                return candidate
            }
            current = current.parentFile
        }
        return File("c:/AgentDesk/tools/src/main/java/com/agentdesk/tools/executor/ToolExecutor.kt")
    }

    @Test
    fun testIntentActionConstantsContract() {
        assertEquals("android.intent.action.SENDTO", Intent.ACTION_SENDTO)
        assertEquals("android.intent.action.DIAL", Intent.ACTION_DIAL)
        assertEquals("android.intent.action.CALL", Intent.ACTION_CALL)
        assertNotEquals("ACTION_DIAL must be distinct from silent ACTION_CALL", Intent.ACTION_DIAL, Intent.ACTION_CALL)
    }

    @Test
    fun testDraftSmsConstructsActionSendToWithSmstoScheme() {
        val toolExecutorFile = findToolExecutorFile()
        assertTrue("ToolExecutor.kt must exist at ${toolExecutorFile.absolutePath}", toolExecutorFile.exists())

        val source = toolExecutorFile.readText()

        // Verify draftSms implementation section
        assertTrue("ToolExecutor must contain draftSms method", source.contains("private suspend fun draftSms("))

        val draftSmsBlock = source.substringAfter("private suspend fun draftSms(").substringBefore("private suspend fun openDialer(")

        assertTrue(
            "draft_sms MUST construct Intent with Intent.ACTION_SENDTO",
            draftSmsBlock.contains("Intent(Intent.ACTION_SENDTO, Uri.parse(\"smsto:\$number\"))")
        )
        assertTrue(
            "draft_sms MUST use smsto: URI scheme for user review",
            draftSmsBlock.contains("smsto:\$number")
        )
        assertTrue(
            "draft_sms MUST include sms_body extra for user review in system UI",
            draftSmsBlock.contains("putExtra(\"sms_body\", body)")
        )

        // Strict negative checks: No silent SMS transmission
        assertFalse(
            "ToolExecutor MUST NOT use SmsManager for silent message transmission",
            source.contains("SmsManager")
        )
        assertFalse(
            "ToolExecutor MUST NOT invoke sendTextMessage",
            source.contains("sendTextMessage")
        )
    }

    @Test
    fun testOpenDialerConstructsActionDialWithTelScheme() {
        val toolExecutorFile = findToolExecutorFile()
        assertTrue("ToolExecutor.kt must exist at ${toolExecutorFile.absolutePath}", toolExecutorFile.exists())

        val source = toolExecutorFile.readText()

        assertTrue("ToolExecutor must contain openDialer method", source.contains("private suspend fun openDialer("))

        val openDialerBlock = source.substringAfter("private suspend fun openDialer(").substringBefore("private fun webSearch(")

        assertTrue(
            "open_dialer MUST construct Intent with Intent.ACTION_DIAL",
            openDialerBlock.contains("Intent(Intent.ACTION_DIAL, Uri.parse(\"tel:\$number\"))")
        )
        assertTrue(
            "open_dialer MUST use tel: URI scheme to open dialer pad",
            openDialerBlock.contains("tel:\$number")
        )

        // Strict negative checks: No silent calling
        assertFalse(
            "ToolExecutor MUST NOT use Intent.ACTION_CALL which places calls without user confirmation",
            source.contains("Intent.ACTION_CALL")
        )
    }

    @Test
    fun testNoForbiddenSilentPermissionsInToolsModule() {
        val toolExecutorFile = findToolExecutorFile()
        var toolsDir: File? = toolExecutorFile
        repeat(6) {
            toolsDir = toolsDir?.parentFile
        }
        val manifest = toolsDir?.let { File(it, "src/main/AndroidManifest.xml") }

        if (manifest != null && manifest.exists()) {
            val manifestText = manifest.readText()
            assertFalse(
                "tools manifest must not request CALL_PHONE permission",
                manifestText.contains("android.permission.CALL_PHONE")
            )
            assertFalse(
                "tools manifest must not request SEND_SMS permission",
                manifestText.contains("android.permission.SEND_SMS")
            )
        }
    }
}
