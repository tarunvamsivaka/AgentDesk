package com.agentdesk.tools.impl

import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.common.model.KnowledgeSourceType
import com.agentdesk.core.persistence.entity.DocumentEntity
import com.agentdesk.core.persistence.entity.KnowledgeSourceEntity
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.core.persistence.entity.TextChunkEntity
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.registry.Tool
import com.agentdesk.tools.registry.ToolCategory
import com.agentdesk.tools.registry.ToolDefinition
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages local notes: creation, appending, and searching.
 *
 * Automatically triggers knowledge indexing into Room FTS text chunks
 * so that all notes are immediately searchable via HybridRetriever and FtsSearchDao.
 */
@Singleton
class QuickNoteTool @Inject constructor(
    private val noteDao: NoteDao,
    private val knowledgeDao: KnowledgeDao? = null
) : Tool {

    override val definition = ToolDefinition(
        id = "quick_note",
        name = "Quick Note",
        description = "Create, append to, and search secure local notes.",
        category = ToolCategory.CONTENT,
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        return when (request.toolId) {
            "append_note" -> appendNote(request.parameters)
            "search_notes" -> searchNotes(request.parameters)
            "create_note" -> createNote(request.parameters)
            else -> {
                when {
                    request.parameters.containsKey("append") -> appendNote(request.parameters)
                    request.parameters.containsKey("query") -> searchNotes(request.parameters)
                    else -> createNote(request.parameters)
                }
            }
        }
    }

    private suspend fun createNote(params: Map<String, String>): ToolResult {
        val text = params["text"]?.trim() ?: params["body"]?.trim()
        if (text.isNullOrBlank()) {
            return ToolResult.Error("create_note", "No note text provided.")
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val title = params["title"]?.trim()?.takeIf { it.isNotBlank() }
            ?: (firstLine ?: text).take(MAX_NOTE_TITLE_CHARS)

        val noteId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val noteEntity = NoteEntity(
            id = noteId,
            title = title,
            body = text,
            createdAt = now
        )
        noteDao.insert(noteEntity)

        // Index note chunk into knowledge database for immediate retrieval
        indexNoteContent(title = title, body = text)

        return ToolResult.Success("create_note", "Note saved: '$title'")
    }

    private suspend fun appendNote(params: Map<String, String>): ToolResult {
        val text = params["text"]?.trim() ?: params["body"]?.trim()
        if (text.isNullOrBlank()) {
            return ToolResult.Error("append_note", "No text to append provided.")
        }

        val targetTitle = params["title"]?.trim()
        val existingNote = if (!targetTitle.isNullOrBlank()) {
            noteDao.findByTitle(targetTitle) ?: noteDao.getLatest()
        } else {
            noteDao.getLatest()
        }

        if (existingNote == null) {
            // If no existing note found, fall back to creating a new note
            return createNote(params)
        }

        val updatedBody = "${existingNote.body}\n$text"
        val updatedNote = existingNote.copy(body = updatedBody)
        noteDao.update(updatedNote)

        // Update indexed chunk
        indexNoteContent(title = updatedNote.title, body = updatedBody)

        return ToolResult.Success("append_note", "Appended to note: '${existingNote.title}'.")
    }

    private suspend fun searchNotes(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.Error("search_notes", "No search query provided.")
        }
        val notes = noteDao.search(query)
        if (notes.isEmpty()) {
            return ToolResult.Success("search_notes", "No notes found matching '$query'.")
        }

        val summary = buildString {
            append("Found ${notes.size} note(s):\n")
            notes.forEachIndexed { i, n ->
                append("${i + 1}. ${n.title} - ${n.body.take(60)}\n")
            }
        }.trim()

        return ToolResult.Success("search_notes", summary)
    }

    private suspend fun indexNoteContent(title: String, body: String) {
        knowledgeDao?.let { dao ->
            try {
                val sourceId = dao.insertSource(
                    KnowledgeSourceEntity(
                        displayName = title,
                        uri = "agentdesk://note/$title",
                        sourceType = KnowledgeSourceType.NOTE,
                        fileSizeBytes = body.length.toLong()
                    )
                )
                val docId = dao.insertDocument(
                    DocumentEntity(
                        sourceId = sourceId,
                        title = "Note: $title",
                        charCount = body.length
                    )
                )
                dao.insertChunks(
                    listOf(
                        TextChunkEntity(
                            documentId = docId,
                            chunkIndex = 0,
                            content = "$title\n$body",
                            charOffset = 0,
                            pageNumber = 1
                        )
                    )
                )
            } catch (_: Exception) {
                // Non-fatal: note insertion succeeded in noteDao
            }
        }
    }

    companion object {
        private const val MAX_NOTE_TITLE_CHARS = 60
    }
}
