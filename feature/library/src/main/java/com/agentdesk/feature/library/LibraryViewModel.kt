package com.agentdesk.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.entity.NoteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** UI state of the library import flow. */
sealed class LibraryUiState {
    object Idle : LibraryUiState()
    object Indexing : LibraryUiState()
    object Ready : LibraryUiState()
    data class Error(val message: String) : LibraryUiState()
}

/** UI representation of a saved note. */
data class NoteUi(
    val id: String,
    val title: String,
    val preview: String,
    val body: String,
    val dateLabel: String
)

/**
 * LibraryViewModel — exposes saved notes via [NoteDao.observeAll] and
 * delegates folder import / index-state observation to
 * [LibraryImportController] so the ViewModel stays testable on the JVM.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    noteDao: NoteDao,
    private val importController: LibraryImportController
) : ViewModel() {

    val notes: Flow<List<NoteUi>> = noteDao.observeAll().map { list -> list.map { it.toUi() } }

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Idle)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            importController.observeIndexState().collect { _uiState.value = it }
        }
    }

    fun onImportFolder(uri: Uri) = importController.enqueueImport(uri)

    private fun NoteEntity.toUi(): NoteUi {
        val truncated = body.length > PREVIEW_CHARS
        return NoteUi(
            id = id,
            title = title.ifBlank { "Untitled" },
            preview = body.take(PREVIEW_CHARS) + if (truncated) "…" else "",
            body = body,
            // Per-call Locale: reflects runtime locale changes (ConstantLocale lint fix)
            dateLabel = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(createdAt))
        )
    }

    companion object {
        private const val PREVIEW_CHARS = 80
    }
}