package com.agentdesk.tools.impl

import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.registry.Tool
import com.agentdesk.tools.registry.ToolCategory
import com.agentdesk.tools.registry.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves a note into the local Room database via [NoteDao].
 * Fully on-device, no intents involved.
 *
 * Risk: LOW — no user confirmation required.
 */
@Singleton
class NoteTool @Inject constructor(
    private val noteDao: NoteDao
) : Tool {

    override val definition = ToolDefinition(
        id = "create_note",
        name = "Create Note",
        description = "Save a text note to local storage.",
        category = ToolCategory.CONTENT,
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        val text = request.parameters["text"]?.trim()
        if (text.isNullOrBlank()) {
            return ToolResult.Error(definition.id, "No note text provided.")
        }
        noteDao.insert(NoteEntity(body = text))
        return ToolResult.Success(definition.id, "Note saved.")
    }
}
