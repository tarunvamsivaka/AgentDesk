package com.agentdesk.knowledge.worker

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agentdesk.core.common.model.IndexStatus
import com.agentdesk.core.common.model.KnowledgeSourceType
import com.agentdesk.core.persistence.dao.FtsSearchDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.entity.DocumentEntity
import com.agentdesk.core.persistence.entity.KnowledgeSourceEntity
import com.agentdesk.core.persistence.entity.TextChunkEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

/**
 * IndexWorker — imports a folder selected via the Storage Access Framework
 * (SAF) and indexes its supported text files into the local Room + FTS4 store.
 *
 * MVP scope:
 * - Walks the selected tree with [DocumentFile].
 * - Indexes only `.txt` and `.md` files (`.pdf` is skipped for now).
 * - Caps the number of files at [MAX_FILES] to bound work.
 * - Chunks each file into [CHUNK_SIZE]-character chunks, stored as
 *   [TextChunkEntity] rows. The FTS4 virtual table is content-synced, so
 *   inserting chunks automatically makes them searchable.
 * - Respects the storage floor by checking for [MIN_FREE_BYTES] free space
 *   before starting (resource governor equivalent for MVP).
 *
 * Privacy: every byte stays on-device; nothing is uploaded.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentDeskDatabase: com.agentdesk.core.persistence.db.AgentDeskDatabase,
    private val knowledgeDao: KnowledgeDao,
    private val ftsSearchDao: FtsSearchDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUriString = inputData.getString(KEY_TREE_URI) ?: return Result.failure()
        if (!hasEnoughFreeStorage(applicationContext)) {
            return Result.retry()
        }

        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUriString))
            ?: return Result.failure()

        val files = collectIndexableFiles(root)
        for (file in files) {
            // Stop cleanly at the top of each file loop instead of mid-file
            if (isStopped) return Result.retry()
            indexFile(file)
        }
        return Result.success()
    }

    private suspend fun indexFile(file: DocumentFile) {
        val text = readText(file) ?: return
        val sourceType = if (file.name?.endsWith(".md", ignoreCase = true) == true) {
            KnowledgeSourceType.MARKDOWN_FILE
        } else {
            KnowledgeSourceType.TEXT_FILE
        }

        val sourceId = knowledgeDao.insertSource(
            KnowledgeSourceEntity(
                displayName = file.name ?: "Untitled",
                uri = file.uri.toString(),
                sourceType = sourceType,
                fileSizeBytes = file.length(),
                indexStatus = IndexStatus.RUNNING
            )
        )

        // Atomic per file: document + chunks + FTS sync either fully apply or not at all
        agentDeskDatabase.withTransaction {
            val documentId = knowledgeDao.insertDocument(
                DocumentEntity(
                    sourceId = sourceId,
                    title = file.name ?: "Untitled",
                    charCount = text.length
                )
            )

            val chunks = text.chunked(CHUNK_SIZE)
            knowledgeDao.insertChunks(
                chunks.mapIndexed { index, content ->
                    TextChunkEntity(
                        documentId = documentId,
                        chunkIndex = index,
                        content = content,
                        charOffset = index * CHUNK_SIZE
                    )
                }
            )
        }

        knowledgeDao.updateIndexStatus(sourceId, IndexStatus.COMPLETED)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun collectIndexableFiles(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val queue = ArrayDeque<DocumentFile>()
        queue.addLast(root)
        while (queue.isNotEmpty() && result.size < MAX_FILES) {
            val current = queue.removeFirst()
            for (child in current.listFiles()) {
                if (result.size >= MAX_FILES) break
                when {
                    child.isDirectory -> queue.addLast(child)
                    child.isFile && isSupportedFile(child.name) -> result.add(child)
                }
            }
        }
        return result
    }

    private fun isSupportedFile(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.endsWith(".txt") || lower.endsWith(".md")
    }

    private fun readText(file: DocumentFile): String? = try {
        applicationContext.contentResolver.openInputStream(file.uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (_: Exception) {
        null
    }

    private fun hasEnoughFreeStorage(context: Context): Boolean {
        return try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumeUuid = storageManager.getStorageVolume(context.filesDir)?.uuid
            if (volumeUuid != null) {
                storageManager.getAllocatableBytes(UUID.fromString(volumeUuid)) >= MIN_FREE_BYTES
            } else {
                statFsFreeBytes(context) >= MIN_FREE_BYTES
            }
        } catch (_: Exception) {
            statFsFreeBytes(context) >= MIN_FREE_BYTES
        }
    }

    private fun statFsFreeBytes(context: Context): Long = try {
        StatFs(context.filesDir.path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    companion object {
        const val WORK_NAME = "agentdesk_index_folder"
        const val KEY_TREE_URI = "tree_uri"

        private const val MAX_FILES = 50
        private const val CHUNK_SIZE = 500
        private const val MIN_FREE_BYTES = 500L * 1024L * 1024L // >500MB free
    }
}