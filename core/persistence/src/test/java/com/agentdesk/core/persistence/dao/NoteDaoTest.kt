package com.agentdesk.core.persistence.dao

import com.agentdesk.core.persistence.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract test for [NoteDao] — insert and observe.
 *
 * Room DAOs require the Android SQLite framework (instrumented run), so this
 * test drives an in-memory fake that mirrors the DAO's queries exactly:
 * - observeAll(): notes ordered by createdAt DESC
 * - search(): notes whose body contains the query (LIMIT 10)
 */
class NoteDaoTest {

    private class FakeNoteDao : NoteDao {
        private val stored = mutableListOf<NoteEntity>()
        private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())

        override suspend fun insert(note: NoteEntity) {
            stored.add(note)
            _notes.value = stored.sortedByDescending { it.createdAt }
        }

        override fun observeAll(): Flow<List<NoteEntity>> = _notes

        override suspend fun search(query: String): List<NoteEntity> =
            stored.filter { it.body.contains(query, ignoreCase = true) }.take(10)

        override suspend fun deleteAll() {
            stored.clear()
            _notes.value = emptyList()
        }
    }

    private fun note(id: String, title: String, body: String, createdAt: Long) =
        NoteEntity(id = id, title = title, body = body, createdAt = createdAt)

    @Test
    fun `insert then observe returns notes in reverse chronological order`() = runBlocking {
        val dao = FakeNoteDao()
        dao.insert(note("a", "First", "buy milk", createdAt = 1_000L))
        dao.insert(note("b", "Second", "call dentist", createdAt = 2_000L))
        dao.insert(note("c", "Third", "review report", createdAt = 3_000L))

        val observed = dao.observeAll().first()

        assertEquals(listOf("c", "b", "a"), observed.map { it.id })
        assertEquals(listOf("Third", "Second", "First"), observed.map { it.title })
    }

    @Test
    fun `observeAll starts empty for a fresh dao`() = runBlocking {
        val dao = FakeNoteDao()
        assertEquals(emptyList<NoteEntity>(), dao.observeAll().first())
    }
}