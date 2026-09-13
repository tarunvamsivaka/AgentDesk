package com.agentdesk.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.command.CommandResult
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.tools.executor.ToolExecutor
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI Models
// ---------------------------------------------------------------------------

enum class MessageSender { USER, AGENT }

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val sender: MessageSender
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isProcessing: Boolean = false,
    val pendingConfirmation: PendingConfirmation? = null
)

data class PendingConfirmation(
    val prompt: String,
    val commandResult: CommandResult.NeedsConfirmation
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val commandGateway: CommandGateway,
    private val toolExecutor: ToolExecutor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onSendClicked() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isProcessing) return

        // Add user message immediately
        appendMessage(ChatMessage(text = text, sender = MessageSender.USER))
        _uiState.update { it.copy(inputText = "", isProcessing = true) }

        viewModelScope.launch {
            val result = commandGateway.process(text, CommandSource.USER_CHAT)
            handleCommandResult(result)
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun onConfirmAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isProcessing = true) }
        viewModelScope.launch {
            val command = pending.commandResult.command
            val toolRequest = ToolRequest(
                toolId = intentToToolId(command.intentName),
                parameters = command.entities
            )
            val toolResult = toolExecutor.execute(toolRequest)
            appendMessage(
                ChatMessage(
                    text = when (toolResult) {
                        is ToolResult.Success   -> toolResult.message
                        is ToolResult.Error     -> "❌ ${toolResult.reason}"
                        is ToolResult.Cancelled -> "Action cancelled."
                    },
                    sender = MessageSender.AGENT
                )
            )
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun onDismissConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
        appendMessage(ChatMessage(text = "Action cancelled.", sender = MessageSender.AGENT))
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private suspend fun handleCommandResult(result: CommandResult) {
        when (result) {
            is CommandResult.Accepted -> {
                val toolRequest = ToolRequest(
                    toolId = intentToToolId(result.command.intentName),
                    parameters = result.command.entities
                )
                val toolResult = toolExecutor.execute(toolRequest)
                appendMessage(
                    ChatMessage(
                        text = when (toolResult) {
                            is ToolResult.Success   -> toolResult.message
                            is ToolResult.Error     -> "❌ ${toolResult.reason}"
                            is ToolResult.Cancelled -> "Action cancelled."
                        },
                        sender = MessageSender.AGENT
                    )
                )
            }
            is CommandResult.NeedsConfirmation -> {
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmation(
                            prompt = result.prompt,
                            commandResult = result
                        )
                    )
                }
            }
            is CommandResult.Rejected -> {
                appendMessage(
                    ChatMessage(
                        text = "🚫 ${result.reason}",
                        sender = MessageSender.AGENT
                    )
                )
            }
            is CommandResult.Unknown -> {
                appendMessage(
                    ChatMessage(
                        text = "I didn't understand \"${result.rawInput}\". Try: \"Set alarm at 7am\", \"Open Maps\", \"Text John saying hello\".",
                        sender = MessageSender.AGENT
                    )
                )
            }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    /** Maps intent dot-notation to tool IDs in ToolRegistry. */
    private fun intentToToolId(intentName: String): String = when (intentName) {
        "system.set_alarm"        -> "set_alarm"
        "system.set_timer"        -> "set_timer"
        "note.create"             -> "create_note"
        "app.open"                -> "open_app"
        "calendar.create_event"   -> "create_calendar_event"
        "message.draft_sms"       -> "draft_sms"
        "phone.dial"              -> "open_dialer"
        "library.search"          -> "library_search"
        "web.search"              -> "web_search"
        "maps.navigate"           -> "navigate_maps"
        "device.health"           -> "device_health"
        else                      -> intentName
    }
}
