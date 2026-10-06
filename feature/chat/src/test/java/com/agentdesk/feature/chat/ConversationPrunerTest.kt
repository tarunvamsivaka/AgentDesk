package com.agentdesk.feature.chat

import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrunerTest {

    @Test
    fun `prune empty list returns empty list`() {
        val result = ConversationPruner.prune(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `prune retains up to 12 messages (6 turns) in chronological order`() {
        val messages = (1..20).map { i ->
            ConversationMessageEntity(
                id = i.toLong(),
                threadId = 1L,
                role = if (i % 2 == 1) "user" else "assistant",
                content = "Message $i",
                createdAt = i * 1000L
            )
        }

        val pruned = ConversationPruner.prune(messages, maxTurns = 6)
        assertEquals(12, pruned.size)
        // Earliest retained message should be message 9, latest message 20
        assertEquals(9L, pruned.first().id)
        assertEquals("Message 9", pruned.first().content)
        assertEquals(20L, pruned.last().id)
        assertEquals("Message 20", pruned.last().content)

        // Verify strictly chronological order
        for (i in 0 until pruned.size - 1) {
            assertTrue(pruned[i].createdAt < pruned[i + 1].createdAt)
        }
    }

    @Test
    fun `prune enforces max character limit by shedding oldest messages`() {
        val longContent = "A".repeat(500)
        val messages = (1..10).map { i ->
            ConversationMessageEntity(
                id = i.toLong(),
                threadId = 1L,
                role = "user",
                content = "[$i] $longContent",
                createdAt = i * 1000L
            )
        }

        // 10 messages * ~504 chars = ~5040 chars. With limit of 1500 chars, only ~3 newest fit.
        val pruned = ConversationPruner.prune(messages, maxTurns = 6, maxChars = 1500)
        assertTrue(pruned.size in 2..3)
        assertEquals(10L, pruned.last().id)
    }

    @Test
    fun `pruneChatMessages operates correctly on UI ChatMessage models`() {
        val chatMessages = (1..15).map { i ->
            ChatMessage(
                id = i.toLong(),
                text = "Turn $i",
                sender = if (i % 2 == 1) MessageSender.USER else MessageSender.AGENT
            )
        }

        val pruned = ConversationPruner.pruneChatMessages(chatMessages, maxTurns = 6)
        assertEquals(12, pruned.size)
        assertEquals(4L, pruned.first().id)
        assertEquals(15L, pruned.last().id)
    }
}
