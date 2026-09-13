package com.agentdesk.feature.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.agentdesk.knowledge.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** UI state of the library import flow. */
sealed class LibraryUiState {
    object Idle : LibraryUiState()
    object Indexing : LibraryUiState()
    object Ready : LibraryUiState()
    data class Error(val message: String) : LibraryUiState()
}

/**
 * LibraryViewModel — enqueues [IndexWorker] for folders picked via SAF and
 * observes the worker's state so the UI can show "Indexing..." / "Library ready".
 *
 * WorkManager 2.9.0 has no Flow observation APIs, so state is polled on the
 * IO dispatcher until it reaches a terminal state.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Idle)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** Enqueues the folder import and starts observing the worker's lifecycle. */
    fun importFolder(uri: Uri) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(workDataOf(IndexWorker.KEY_TREE_URI to uri.toString()))
            .build()
        workManager.enqueueUniqueWork(IndexWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        observeIndexing()
    }

    private fun observeIndexing() {
        viewModelScope.launch {
            while (isActive) {
                val state = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(IndexWorker.WORK_NAME).get()
                        .firstOrNull()?.state
                }
                _uiState.value = toUiState(state)
                if (state in TERMINAL_STATES) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun toUiState(state: WorkInfo.State?): LibraryUiState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> LibraryUiState.Indexing
        WorkInfo.State.SUCCEEDED -> LibraryUiState.Ready
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> LibraryUiState.Error("Import failed.")
        null -> LibraryUiState.Idle
    }

    companion object {
        private val TERMINAL_STATES = setOf(
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED
        )
        private const val POLL_INTERVAL_MS = 500L
    }
}