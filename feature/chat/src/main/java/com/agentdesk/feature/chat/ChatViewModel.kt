package com.agentdesk.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.core.common.model.CommandSource
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
    val command: Command
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val commandGateway: CommandGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onSendClicked() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isProcessing) return

        // Add user message immediately, then show loading state while executing
        appendMessage(ChatMessage(text = text, sender = MessageSender.USER))
        _uiState.update { it.copy(inputText = "", isProcessing = true) }

        viewModelScope.launch {
            handleOutcome(commandGateway.submitText(text, CommandSource.USER_CHAT))
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun onConfirmAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isProcessing = true) }
        viewModelScope.launch {
            val outcome = commandGateway.executeConfirmed(pending.command)
            val message = (outcome as? CommandOutcome.Executed)?.message
                ?: "Action could not be completed."
            appendMessage(ChatMessage(text = message, sender = MessageSender.AGENT))
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

    private fun handleOutcome(outcome: CommandOutcome) {
        when (outcome) {
            is CommandOutcome.Executed ->
                appendMessage(
                    ChatMessage(text = outcome.message, sender = MessageSender.AGENT)
                )
            is CommandOutcome.NeedsUserConfirmation ->
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmation(
                            prompt = outcome.prompt,
                            command = outcome.command
                        )
                    )
                }
            is CommandOutcome.Rejected ->
                appendMessage(
                    ChatMessage(text = "🚫 ${outcome.reason}", sender = MessageSender.AGENT)
                )
            is CommandOutcome.Unknown ->
                appendMessage(
                    ChatMessage(
                        text = "I didn't understand \"${outcome.rawInput}\". Try: \"Set alarm at 7am\", \"Open Maps\", \"Note down buy milk\".",
                        sender = MessageSender.AGENT
                    )
                )
        }
    }

    private fun appendMessage(message: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }
}
