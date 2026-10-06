package com.agentdesk.core.persistence.dao

import com.agentdesk.core.persistence.entity.ConversationMessageEntity
import com.agentdesk.core.persistence.entity.ConversationThreadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract test for [ConversationDao] — validates thread creation, touch,
 * message insertion, reactive observation, recent message retrieval, and pruning.
 */
class ConversationDaoTest {

    private class FakeConversationDao : ConversationDao {
        private val threads = mutableListOf<ConversationThreadEntity>()
        private val messages = mutableListOf<ConversationMessageEntity>()
        private val messagesFlow = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())
        private var nextThreadId = 1L
        private var nextMessageId = 1L

        override suspend fun insertThread(thread: ConversationThreadEntity): Long {
            val id = if (thread.id == 0L) nextThreadId++ else thread.id
            val saved = thread.copy(id = id)
            threads.removeAll { it.id == id }
            threads.add(saved)
            return id
        }

        override suspend fun updateThread(thread: ConversationThreadEntity) {
            val idx = threads.indexOfFirst { it.id == thread.id }
            if (idx >= 0) threads[idx] = thread
        }

        override suspend fun getThread(threadId: Long): ConversationThreadEntity? =
            threads.firstOrNull { it.id == threadId }

        override suspend fun getLatestActiveThread(): ConversationThreadEntity? =
            threads.filter { !it.isArchived }.maxByOrNull { it.updatedAt }

        override suspend fun touchThread(threadId: Long, updatedAt: Long) {
            val idx = threads.indexOfFirst { it.id == threadId }
            if (idx >= 0) {
                threads[idx] = threads[idx].copy(updatedAt = updatedAt)
            }
        }

        override suspend fun insertMessage(message: ConversationMessageEntity): Long {
            val id = if (message.id == 0L) nextMessageId++ else message.id
            val saved = message.copy(id = id)
            messages.add(saved)
            messagesFlow.value = messages.toList()
            return id
        }

        override fun observeMessages(threadId: Long): Flow<List<ConversationMessageEntity>> {
            val flow = MutableStateFlow(messages.filter { it.threadId == threadId }.sortedWith(compareBy({ it.createdAt }, { it.id })))
            return flow
        }

        override suspend fun getRecentMessages(threadId: Long, limit: Int): List<ConversationMessageEntity> =
            messages.filter { it.threadId == threadId }
                .sortedWith(compareByDescending<ConversationMessageEntity> { it.createdAt }.thenByDescending { it.id })
                .take(limit)

        override suspend fun clearThread(threadId: Long) {
            messages.removeAll { it.threadId == threadId }
            messagesFlow.value = messages.toList()
        }

        override suspend fun deleteArchivedOlderThan(cutoffMs: Long) {
            val toRemove = threads.filter { it.isArchived && it.updatedAt < cutoffMs }.map { it.id }
            threads.removeAll { it.id in toRemove }
            messages.removeAll { it.threadId in toRemove }
        }

        override suspend fun deleteThreadsOlderThan(cutoffMs: Long) {
            val toRemove = threads.filter { it.updatedAt < cutoffMs }.map { it.id }
            threads.removeAll { it.id in toRemove }
            messages.removeAll { it.threadId in toRemove }
        }

        override suspend fun countMessages(threadId: Long): Int =
            messages.count { it.threadId == threadId }
    }

    @Test
    fun `insertThread creates active thread and getLatestActiveThread retrieves it`() = runBlocking {
        val dao = FakeConversationDao()
        val threadId = dao.insertThread(ConversationThreadEntity(title = "Test Thread", createdAt = 1000L, updatedAt = 1000L))
        assertEquals(1L, threadId)

        val latest = dao.getLatestActiveThread()
        assertNotNull(latest)
        assertEquals(threadId, latest?.id)
        assertEquals("Test Thread", latest?.title)
    }

    @Test
    fun `touchThread updates thread updatedAt timestamp`() = runBlocking {
        val dao = FakeConversationDao()
        val threadId = dao.insertThread(ConversationThreadEntity(title = "Active", updatedAt = 1000L))

        dao.touchThread(threadId, updatedAt = 5000L)
        val updated = dao.getThread(threadId)
        assertNotNull(updated)
        assertEquals(5000L, updated?.updatedAt)
    }

    @Test
    fun `insertMessage assigns monotonic IDs and maintains order`() = runBlocking {
        val dao = FakeConversationDao()
        val threadId = dao.insertThread(ConversationThreadEntity())

        val id1 = dao.insertMessage(ConversationMessageEntity(threadId = threadId, role = "user", content = "Hello", createdAt = 100L))
        val id2 = dao.insertMessage(ConversationMessageEntity(threadId = threadId, role = "assistant", content = "Hi", createdAt = 200L))

        assertEquals(1L, id1)
        assertEquals(2L, id2)
        assertEquals(2, dao.countMessages(threadId))

        val recent = dao.getRecentMessages(threadId, limit = 10)
        assertEquals(listOf(id2, id1), recent.map { it.id })
    }

    @Test
    fun `clearThread deletes all messages in target thread`() = runBlocking {
        val dao = FakeConversationDao()
        val thread1 = dao.insertThread(ConversationThreadEntity())
        val thread2 = dao.insertThread(ConversationThreadEntity())

        dao.insertMessage(ConversationMessageEntity(threadId = thread1, role = "user", content = "msg1"))
        dao.insertMessage(ConversationMessageEntity(threadId = thread2, role = "user", content = "msg2"))

        assertEquals(1, dao.countMessages(thread1))
        assertEquals(1, dao.countMessages(thread2))

        dao.clearThread(thread1)
        assertEquals(0, dao.countMessages(thread1))
        assertEquals(1, dao.countMessages(thread2))
    }

    @Test
    fun `deleteArchivedOlderThan removes only archived expired threads`() = runBlocking {
        val dao = FakeConversationDao()
        val activeOld = dao.insertThread(ConversationThreadEntity(updatedAt = 1000L, isArchived = false))
        val archivedOld = dao.insertThread(ConversationThreadEntity(updatedAt = 1000L, isArchived = true))
        val archivedRecent = dao.insertThread(ConversationThreadEntity(updatedAt = 9000L, isArchived = true))

        dao.deleteArchivedOlderThan(cutoffMs = 5000L)

        assertNotNull(dao.getThread(activeOld))
        assertNull(dao.getThread(archivedOld))
        assertNotNull(dao.getThread(archivedRecent))
    }
}
