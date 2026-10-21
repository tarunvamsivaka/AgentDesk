package com.agentdesk.tools

import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.impl.CalendarEventTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventToolTest {

    @Test
    fun testValidationRules() = runBlocking {
        val tool = CalendarEventTool()

        // Blank title
        val blankResult = tool.execute(
            ToolRequest(toolId = "create_calendar_event", parameters = mapOf("title" to "   "))
        )
        assertTrue(blankResult is ToolResult.Error)
        assertEquals("Event title must not be blank.", (blankResult as ToolResult.Error).reason)

        // Title > 256 chars
        val longResult = tool.execute(
            ToolRequest(toolId = "create_calendar_event", parameters = mapOf("title" to "A".repeat(300)))
        )
        assertTrue(longResult is ToolResult.Error)

        // End time before start time
        val invalidTimeResult = tool.execute(
            ToolRequest(
                toolId = "create_calendar_event",
                parameters = mapOf(
                    "title" to "Sprint Planning",
                    "beginTimeMillis" to "2000",
                    "endTimeMillis" to "1000"
                )
            )
        )
        assertTrue(invalidTimeResult is ToolResult.Error)
        assertTrue((invalidTimeResult as ToolResult.Error).reason.contains("cannot be before begin time"))
    }

    @Test
    fun testFallbackIntentWhenContextIsNull() = runBlocking {
        val tool = CalendarEventTool()
        val result = tool.execute(
            ToolRequest(
                toolId = "create_calendar_event",
                parameters = mapOf(
                    "title" to "Team Sync",
                    "description" to "Weekly catch-up",
                    "location" to "Meeting Room A",
                    "beginTimeMillis" to "1700000000000",
                    "endTimeMillis" to "1700003600000"
                )
            )
        )

        assertTrue(result is ToolResult.Success)
        assertEquals("Calendar event 'Team Sync' drafted. Tap Save to schedule.", (result as ToolResult.Success).message)
    }

    @Test
    fun testDefinitionMetadata() {
        val tool = CalendarEventTool()
        assertEquals("calendar_event", tool.definition.id)
        assertEquals("Calendar Event", tool.definition.name)
        assertTrue(tool.definition.requiresConfirmation)
        assertEquals(RiskLevel.MEDIUM, tool.definition.riskLevel)
    }
}
