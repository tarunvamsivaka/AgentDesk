package com.agentdesk.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.tools.contact.ContactResolver
import com.agentdesk.tools.contact.Resolution
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.impl.AlarmTimerTool
import com.agentdesk.tools.impl.CalendarEventTool
import com.agentdesk.tools.impl.CommunicationTool
import com.agentdesk.tools.impl.QuickNoteTool
import com.agentdesk.tools.impl.SystemSettingsTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying transparent system Intent dispatch and safety across all system tools:
 * 1. draft_sms & open_dialer construct transparent user-facing Intents (never silent sending/calling).
 * 2. AlarmTimerTool validates hour, minute, and duration bounds.
 * 3. CalendarEventTool validates title and chronologically valid time ranges.
 * 4. CommunicationTool supports dial, SMS, and email with contact resolution.
 * 5. QuickNoteTool supports create, append, and search operations.
 * 6. SystemSettingsTool enforces strict allowlist of settings actions.
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

    @Test
    fun testAlarmTimerToolValidatesParameters() = runBlocking {
        val tool = AlarmTimerTool()

        // Invalid hour (> 23)
        val invalidHour = tool.execute(
            ToolRequest(toolId = "set_alarm", parameters = mapOf("hour" to "25", "minute" to "30"))
        )
        assertTrue(invalidHour is ToolResult.Error)
        assertTrue((invalidHour as ToolResult.Error).reason.contains("Hour must be between 0 and 23"))

        // Invalid minute (> 59)
        val invalidMinute = tool.execute(
            ToolRequest(toolId = "set_alarm", parameters = mapOf("hour" to "10", "minute" to "75"))
        )
        assertTrue(invalidMinute is ToolResult.Error)

        // Invalid timer duration (< 1 or > 86400)
        val invalidTimer = tool.execute(
            ToolRequest(toolId = "set_timer", parameters = mapOf("length" to "0"))
        )
        assertTrue(invalidTimer is ToolResult.Error)

        val oversizedTimer = tool.execute(
            ToolRequest(toolId = "set_timer", parameters = mapOf("length" to "100000"))
        )
        assertTrue(oversizedTimer is ToolResult.Error)
    }

    @Test
    fun testCalendarEventToolValidatesTitleAndTimeOrder() = runBlocking {
        val tool = CalendarEventTool()

        // Blank title rejected
        val blankTitle = tool.execute(
            ToolRequest(toolId = "create_calendar_event", parameters = mapOf("title" to "   "))
        )
        assertTrue(blankTitle is ToolResult.Error)

        // Invalid time range (end before start)
        val invalidTimes = tool.execute(
            ToolRequest(
                toolId = "create_calendar_event",
                parameters = mapOf(
                    "title" to "Team Sync",
                    "beginTimeMillis" to "10000",
                    "endTimeMillis" to "5000"
                )
            )
        )
        assertTrue(invalidTimes is ToolResult.Error)
        assertTrue((invalidTimes as ToolResult.Error).reason.contains("End time"))
    }

    @Test
    fun testCommunicationToolRejectsBlankRecipient() = runBlocking {
        val tool = CommunicationTool()

        val emptySms = tool.execute(ToolRequest(toolId = "draft_sms", parameters = emptyMap()))
        assertTrue(emptySms is ToolResult.Error)

        val emptyDial = tool.execute(ToolRequest(toolId = "open_dialer", parameters = emptyMap()))
        assertTrue(emptyDial is ToolResult.Error)

        val emptyEmail = tool.execute(ToolRequest(toolId = "draft_email", parameters = emptyMap()))
        assertTrue(emptyEmail is ToolResult.Error)
    }

    @Test
    fun testQuickNoteToolCreateAppendSearch() = runBlocking {
        val fakeDao = object : NoteDao {
            val notes = mutableListOf<NoteEntity>()
            override suspend fun insert(note: NoteEntity) { notes.add(note) }
            override suspend fun update(note: NoteEntity) {
                val index = notes.indexOfFirst { it.id == note.id }
                if (index != -1) notes[index] = note
            }
            override fun observeAll(): Flow<List<NoteEntity>> = emptyFlow()
            override suspend fun getLatest(): NoteEntity? = notes.lastOrNull()
            override suspend fun findByTitle(title: String): NoteEntity? =
                notes.firstOrNull { it.title.contains(title, ignoreCase = true) }
            override suspend fun search(query: String): List<NoteEntity> =
                notes.filter { it.body.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }
            override suspend fun deleteAll() { notes.clear() }
        }

        val tool = QuickNoteTool(fakeDao)

        // 1. Create note
        val createResult = tool.execute(
            ToolRequest(toolId = "create_note", parameters = mapOf("text" to "Buy groceries tomorrow"))
        )
        assertTrue(createResult is ToolResult.Success)
        assertEquals(1, fakeDao.notes.size)
        assertTrue(fakeDao.notes.first().body.contains("Buy groceries"))

        // 2. Append note
        val appendResult = tool.execute(
            ToolRequest(toolId = "append_note", parameters = mapOf("text" to "Also get milk"))
        )
        assertTrue(appendResult is ToolResult.Success)
        assertTrue(fakeDao.notes.first().body.contains("Also get milk"))

        // 3. Search notes
        val searchResult = tool.execute(
            ToolRequest(toolId = "search_notes", parameters = mapOf("query" to "groceries"))
        )
        assertTrue(searchResult is ToolResult.Success)
        assertTrue((searchResult as ToolResult.Success).message.contains("Found 1 note"))
    }

    @Test
    fun testSystemSettingsToolAllowlist() = runBlocking {
        val tool = SystemSettingsTool()

        // Allowed targets
        val wifiResult = tool.execute(
            ToolRequest(toolId = "system_settings", parameters = mapOf("setting" to "wifi"))
        )
        assertTrue(wifiResult is ToolResult.Success)

        val bluetoothResult = tool.execute(
            ToolRequest(toolId = "system_settings", parameters = mapOf("setting" to "bluetooth"))
        )
        assertTrue(bluetoothResult is ToolResult.Success)

        // Forbidden arbitrary action string
        val forbiddenResult = tool.execute(
            ToolRequest(toolId = "system_settings", parameters = mapOf("setting" to "com.malicious.BACKDOOR_INTENT"))
        )
        assertTrue(forbiddenResult is ToolResult.Error)
        assertTrue((forbiddenResult as ToolResult.Error).reason.contains("not in the permitted allowlist"))
    }
}
