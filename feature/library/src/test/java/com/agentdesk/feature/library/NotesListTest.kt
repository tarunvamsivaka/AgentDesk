package com.agentdesk.feature.library

import android.net.Uri
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.entity.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LibraryViewModel] — the notes list emitted from NoteDao.
 *
 * Uses an in-memory fake NoteDao (Room DAOs need the Android SQLite
 * framework) and a fake import controller, so the ViewModel is driven purely
 * on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListTest {

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

    private class FakeImportController : LibraryImportController {
        override fun enqueueImport(uri: Uri) {}
        override fun observeIndexState(): Flow<LibraryUiState> = flowOf(LibraryUiState.Idle)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun note(id: String, title: String, body: String, createdAt: Long) =
        NoteEntity(id = id, title = title, body = body, createdAt = createdAt)

    @Test
    fun `viewmodel emits notes from dao in reverse chronological order`() = runBlocking {
        val dao = FakeNoteDao()
        val viewModel = LibraryViewModel(dao, FakeImportController())

        dao.insert(note("a", "First", "buy milk and eggs", createdAt = 1_000L))
        dao.insert(note("b", "Second", "call the dentist", createdAt = 2_000L))

        val notes = viewModel.notes.first()

        assertEquals(2, notes.size)
        assertEquals(listOf("b", "a"), notes.map { it.id })
        assertEquals("Second", notes[0].title)
        assertEquals("First", notes[1].title)
    }

    @Test
    fun `note preview truncates body to 80 chars with ellipsis`() = runBlocking {
        val dao = FakeNoteDao()
        val viewModel = LibraryViewModel(dao, FakeImportController())

        val longBody = "word ".repeat(40) // 200 chars
        dao.insert(note("n1", "Long", longBody, createdAt = 1_000L))

        val notes = viewModel.notes.first()

        assertEquals(1, notes.size)
        assertEquals(81, notes[0].preview.length)
        assertTrue(notes[0].preview.endsWith("…"))
    }

    @Test
    fun `short body note preview has no ellipsis`() = runBlocking {
        val dao = FakeNoteDao()
        val viewModel = LibraryViewModel(dao, FakeImportController())

        dao.insert(note("n1", "Short", "buy milk", createdAt = 1_000L))

        val notes = viewModel.notes.first()

        assertEquals("buy milk", notes[0].preview)
        assertEquals("buy milk", notes[0].body)
    }

    @Test
    fun `blank title falls back to Untitled`() = runBlocking {
        val dao = FakeNoteDao()
        val viewModel = LibraryViewModel(dao, FakeImportController())

        dao.insert(note("n1", "", "some body", createdAt = 1_000L))

        val notes = viewModel.notes.first()

        assertEquals("Untitled", notes[0].title)
    }

    @Test
    fun `date label is formatted for display`() = runBlocking {
        val dao = FakeNoteDao()
        val viewModel = LibraryViewModel(dao, FakeImportController())

        dao.insert(note("n1", "T", "b", createdAt = 0L)) // Jan 1 1970

        val notes = viewModel.notes.first()

        assertTrue("Expected a formatted date, got: '${notes[0].dateLabel}'", notes[0].dateLabel.isNotBlank())
        assertTrue(notes[0].dateLabel.contains(","))
    }
}