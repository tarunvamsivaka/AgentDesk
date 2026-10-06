package com.agentdesk.feature.chat

import com.agentdesk.core.persistence.entity.ConversationMessageEntity

/**
 * Utility for multi-turn conversation pruning.
 *
 * Implements dialogue-turn windowing:
 * - Extracts up to the last [maxTurns] dialogue turns (default 6 turns = up to 12 messages).
 * - Enforces a safety ceiling on total character/token budget (default 8,192 chars ≈ 2,048 tokens).
 * - Preserves strict chronological order (created_at ASC) of retained messages.
 */
object ConversationPruner {
    const val DEFAULT_MAX_TURNS = 6
    const val DEFAULT_MAX_MESSAGES = DEFAULT_MAX_TURNS * 2 // 12 messages
    const val MAX_CONTEXT_CHARS = 8192 // ~2048 tokens safety ceiling (approx 4 chars per token)

    /**
     * Prunes a list of [ConversationMessageEntity] to the most recent [maxTurns] turns,
     * bounded by [maxChars].
     *
     * @param messages List of messages sorted in chronological order (created_at ASC).
     * @param maxTurns Maximum dialogue turns to keep (1 turn = 1 user message + 1 assistant reply).
     * @param maxChars Maximum character limit across all returned messages.
     * @return Chronologically ordered pruned list of messages.
     */
    fun prune(
        messages: List<ConversationMessageEntity>,
        maxTurns: Int = DEFAULT_MAX_TURNS,
        maxChars: Int = MAX_CONTEXT_CHARS
    ): List<ConversationMessageEntity> {
        if (messages.isEmpty()) return emptyList()

        val maxMessages = maxTurns * 2
        val recent = messages.takeLast(maxMessages)

        var totalChars = 0
        val pruned = mutableListOf<ConversationMessageEntity>()
        // Traverse backwards from newest to oldest to preserve the most recent context
        for (msg in recent.reversed()) {
            if (totalChars + msg.content.length > maxChars && pruned.isNotEmpty()) {
                break
            }
            totalChars += msg.content.length
            pruned.add(msg)
        }
        return pruned.reversed()
    }

    /**
     * Overload for UI-level [ChatMessage] list.
     */
    fun pruneChatMessages(
        messages: List<ChatMessage>,
        maxTurns: Int = DEFAULT_MAX_TURNS,
        maxChars: Int = MAX_CONTEXT_CHARS
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val maxMessages = maxTurns * 2
        val recent = messages.takeLast(maxMessages)

        var totalChars = 0
        val pruned = mutableListOf<ChatMessage>()
        for (msg in recent.reversed()) {
            if (totalChars + msg.text.length > maxChars && pruned.isNotEmpty()) {
                break
            }
            totalChars += msg.text.length
            pruned.add(msg)
        }
        return pruned.reversed()
    }
}
