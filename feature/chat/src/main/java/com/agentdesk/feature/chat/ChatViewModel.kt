package com.agentdesk.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.ConversationDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import com.agentdesk.core.persistence.entity.ConversationThreadEntity
import com.agentdesk.core.security.Redactor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI Models
// ---------------------------------------------------------------------------

enum class MessageSender { USER, AGENT }

/**
 * UI representation of a dialogue message.
 *
 * [id] is backed directly by Room's monotonic SQLite autoincrement ID,
 * ensuring unique, collision-free Compose LazyColumn keys across app restarts.
 */
data class ChatMessage(
    val id: Long = 0L,
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
    val command: Command,
    val confirmationId: Long = 0L
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class ChatViewModel(
    private val commandGateway: CommandGateway,
    private val confirmationDao: ConfirmationDao,
    private val conversationDao: ConversationDao,
    private val auditDao: AuditDao,
    private val redactor: Redactor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    @Inject
    constructor(
        commandGateway: CommandGateway,
        confirmationDao: ConfirmationDao,
        conversationDao: ConversationDao,
        auditDao: AuditDao,
        redactor: Redactor
    ) : this(
        commandGateway = commandGateway,
        confirmationDao = confirmationDao,
        conversationDao = conversationDao,
        auditDao = auditDao,
        redactor = redactor,
        ioDispatcher = Dispatchers.IO
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val threadMutex = Mutex()
    private var currentThreadId: Long = 0L

    init {
        viewModelScope.launch(ioDispatcher) {
            val threadId = getOrCreateThreadId()
            conversationDao.observeMessages(threadId).collect { entities ->
                _uiState.update { state ->
                    state.copy(
                        messages = entities.map { entity ->
                            ChatMessage(
                                id = entity.id,
                                text = entity.content,
                                sender = if (entity.role == "user") MessageSender.USER else MessageSender.AGENT
                            )
                        }
                    )
                }
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onSendClicked() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isProcessing) return

        _uiState.update { it.copy(inputText = "", isProcessing = true) }

        viewModelScope.launch(ioDispatcher) {
            val cleanText = redactor.redact(text)
            val threadId = getOrCreateThreadId()

            // Persist user message to Room (Room Flow emits this back into _uiState.messages)
            conversationDao.insertMessage(
                ConversationMessageEntity(
                    threadId = threadId,
                    role = "user",
                    content = cleanText
                )
            )
            conversationDao.touchThread(threadId)

            val outcome = commandGateway.submitText(cleanText, CommandSource.USER_CHAT, threadId = threadId)
            handleOutcome(outcome, threadId)
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun onConfirmAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isProcessing = true) }
        viewModelScope.launch(ioDispatcher) {
            val threadId = getOrCreateThreadId()
            // Persist the user's approval (user_confirmed = true)
            confirmationDao.recordResponse(pending.confirmationId, confirmed = true)
            val outcome = commandGateway.executeConfirmed(pending.command, threadId = threadId)
            val message = (outcome as? CommandOutcome.Executed)?.message
                ?: "Action could not be completed."

            conversationDao.insertMessage(
                ConversationMessageEntity(
                    threadId = threadId,
                    role = "assistant",
                    content = message
                )
            )
            conversationDao.touchThread(threadId)
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun onDismissConfirmation() {
        val pending = _uiState.value.pendingConfirmation
        _uiState.update { it.copy(pendingConfirmation = null) }
        viewModelScope.launch(ioDispatcher) {
            val threadId = getOrCreateThreadId()
            pending?.let {
                // Record user cancellation in confirmation DAO
                confirmationDao.recordResponse(it.confirmationId, confirmed = false)
                // Mandatory audit logging for user dismissal
                auditDao.insert(
                    AuditEventEntity(
                        eventType = "USER_CANCELLED",
                        actor = "USER",
                        targetType = "INTENT",
                        targetId = it.command.intentName,
                        summary = "User dismissed confirmation for ${it.command.intentName}",
                        riskLevel = it.command.riskLevel
                    )
                )
            }
            conversationDao.insertMessage(
                ConversationMessageEntity(
                    threadId = threadId,
                    role = "assistant",
                    content = "Action cancelled."
                )
            )
            conversationDao.touchThread(threadId)
        }
    }

    fun clearConversation() {
        viewModelScope.launch(ioDispatcher) {
            val threadId = getOrCreateThreadId()
            conversationDao.clearThread(threadId)
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private suspend fun getOrCreateThreadId(): Long = threadMutex.withLock {
        if (currentThreadId != 0L) return@withLock currentThreadId
        val existing = conversationDao.getLatestActiveThread()
        val threadId = existing?.id ?: conversationDao.insertThread(
            ConversationThreadEntity(title = "Default Conversation")
        )
        currentThreadId = threadId
        threadId
    }

    private suspend fun handleOutcome(outcome: CommandOutcome, threadId: Long) {
        when (outcome) {
            is CommandOutcome.Executed -> {
                conversationDao.insertMessage(
                    ConversationMessageEntity(
                        threadId = threadId,
                        role = "assistant",
                        content = outcome.message
                    )
                )
                conversationDao.touchThread(threadId)
            }
            is CommandOutcome.NeedsUserConfirmation -> {
                // Persist the prompt (user_confirmed = null until the user responds)
                val recordId = confirmationDao.insert(
                    ConfirmationRecordEntity(
                        taskId = 0L,
                        promptShown = outcome.prompt,
                        userConfirmed = null,
                        promptedAt = System.currentTimeMillis()
                    )
                )
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmation(
                            prompt = outcome.prompt,
                            command = outcome.command,
                            confirmationId = recordId
                        )
                    )
                }
            }
            is CommandOutcome.Rejected -> {
                conversationDao.insertMessage(
                    ConversationMessageEntity(
                        threadId = threadId,
                        role = "assistant",
                        content = "🚫 ${outcome.reason}"
                    )
                )
                conversationDao.touchThread(threadId)
            }
            is CommandOutcome.Unknown -> {
                conversationDao.insertMessage(
                    ConversationMessageEntity(
                        threadId = threadId,
                        role = "assistant",
                        content = "I didn't understand \"${outcome.rawInput}\". Try: \"Set alarm at 7am\", \"Open Maps\", \"Note down buy milk\"."
                    )
                )
                conversationDao.touchThread(threadId)
            }
        }
    }
}
