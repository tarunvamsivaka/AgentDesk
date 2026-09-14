package com.agentdesk.feature.library

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.agentdesk.knowledge.worker.IndexWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port for starting a SAF folder import and observing its indexing state.
 * Kept as an interface so [LibraryViewModel] stays testable on the JVM
 * (no Android framework types are touched in the ViewModel).
 */
interface LibraryImportController {

    /** Enqueues [IndexWorker] for the selected folder. */
    fun enqueueImport(uri: Uri)

    /** Observes the import/index lifecycle as UI states. */
    fun observeIndexState(): Flow<LibraryUiState>
}

/**
 * WorkManager-backed implementation. WorkManager 2.9.0 has no Flow
 * observation APIs, so state is polled on the IO dispatcher until a
 * terminal state is reached.
 */
@Singleton
class WorkManagerImportController @Inject constructor(
    @ApplicationContext private val context: Context
) : LibraryImportController {

    private val workManager by lazy { WorkManager.getInstance(context) }

    override fun enqueueImport(uri: Uri) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(workDataOf(IndexWorker.KEY_TREE_URI to uri.toString()))
            .build()
        workManager.enqueueUniqueWork(IndexWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun observeIndexState(): Flow<LibraryUiState> = flow {
        while (currentCoroutineContext().isActive) {
            val state = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(IndexWorker.WORK_NAME).get()
                    .firstOrNull()?.state
            }
            emit(toUiState(state))
            if (state in TERMINAL_STATES) break
            delay(POLL_INTERVAL_MS)
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

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    @Provides
    @Singleton
    fun provideImportController(controller: WorkManagerImportController): LibraryImportController =
        controller
}