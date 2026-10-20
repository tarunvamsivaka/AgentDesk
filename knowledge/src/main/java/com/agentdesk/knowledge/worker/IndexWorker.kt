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
import com.agentdesk.core.persistence.entity.EmbeddingVectorEntity
import com.agentdesk.knowledge.chunking.SemanticWindowChunker
import com.agentdesk.knowledge.vector.EmbeddingGenerator
import com.agentdesk.knowledge.vector.FastVectorMath
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * IndexWorker — imports a folder selected via the Storage Access Framework
 * (SAF) and indexes its supported text files into the local Room + FTS4 store and vector embeddings.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentDeskDatabase: com.agentdesk.core.persistence.db.AgentDeskDatabase,
    private val knowledgeDao: KnowledgeDao,
    private val ftsSearchDao: FtsSearchDao,
    private val embeddingGenerator: EmbeddingGenerator,
    private val resourceGovernor: com.agentdesk.core.device.ResourceGovernor? = null
) : CoroutineWorker(appContext, params) {

    private val chunker = SemanticWindowChunker()


    override suspend fun doWork(): Result {
        val treeUriString = inputData.getString(KEY_TREE_URI) ?: return Result.failure()
        if (!hasEnoughFreeStorage(applicationContext)) {
            return Result.retry()
        }

        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUriString))
            ?: return Result.failure()

        val files = collectIndexableFiles(root)
        try {
            for (file in files) {
                // Stop cleanly at the top of each file loop instead of mid-file
                if (isStopped) return Result.retry()
                indexFile(file)
                if (isStopped) return Result.retry()
            }
            return Result.success()
        } catch (e: CancellationException) {
            return Result.retry()
        } catch (_: Exception) {
            return Result.failure()
        }
    }

    private suspend fun indexFile(file: DocumentFile) {
        if (isStopped) return
        if (file.length() > MAX_FILE_SIZE_BYTES) return
        val text = readText(file) ?: return
        val sourceType = when {
            file.name?.endsWith(".md", ignoreCase = true) == true -> KnowledgeSourceType.MARKDOWN_FILE
            file.name?.endsWith(".pdf", ignoreCase = true) == true -> KnowledgeSourceType.PDF_FILE
            else -> KnowledgeSourceType.TEXT_FILE
        }

        var sourceId: Long? = null
        try {
            sourceId = knowledgeDao.insertSource(
                KnowledgeSourceEntity(
                    displayName = file.name ?: "Untitled",
                    uri = file.uri.toString(),
                    sourceType = sourceType,
                    fileSizeBytes = file.length(),
                    indexStatus = IndexStatus.RUNNING
                )
            )

            if (isStopped) {
                knowledgeDao.updateIndexStatus(sourceId, IndexStatus.FAILED)
                return
            }

            // Atomic per file: document + chunks + FTS sync either fully apply or not at all
            // Atomic per file: document + chunks + embeddings + FTS sync either fully apply or not at all
            agentDeskDatabase.withTransaction {
                val documentId = knowledgeDao.insertDocument(
                    DocumentEntity(
                        sourceId = sourceId,
                        title = file.name ?: "Untitled",
                        charCount = text.length
                    )
                )

                val chunks = chunker.chunk(
                    title = file.name ?: "Untitled",
                    fullText = text
                )

                val chunkEntities = chunks.map { c ->
                    TextChunkEntity(
                        documentId = documentId,
                        chunkIndex = c.index,
                        content = c.content,
                        charOffset = c.charOffset,
                        pageNumber = c.pageNumber
                    )
                }
                knowledgeDao.insertChunks(chunkEntities)

                // Fetch inserted chunks to obtain their auto-generated primary key IDs
                val savedChunks = knowledgeDao.chunksForDocument(documentId)
                if (savedChunks.isNotEmpty()) {
                    val batchSize = resourceGovernor?.resolveOptimalBatchSize() ?: 16
                    val embeddings = mutableListOf<EmbeddingVectorEntity>()
                    val chunkBatches = savedChunks.chunked(batchSize)
                    for (batch in chunkBatches) {
                        val texts = batch.map { it.content }
                        val vectors = embeddingGenerator.generateEmbeddings(texts)
                        for (i in batch.indices) {
                            val sc = batch[i]
                            val vec = vectors[i]
                            val blob = FastVectorMath.toByteArray(vec)
                            embeddings.add(
                                EmbeddingVectorEntity(
                                    chunkId = sc.id,
                                    vectorBlob = blob,
                                    modelVersion = embeddingGenerator.modelVersion
                                )
                            )
                        }
                    }
                    if (embeddings.isNotEmpty()) {
                        knowledgeDao.insertEmbeddings(embeddings)
                    }
                }
            }

            if (isStopped) {
                knowledgeDao.updateIndexStatus(sourceId, IndexStatus.FAILED)
            } else {
                knowledgeDao.updateIndexStatus(sourceId, IndexStatus.COMPLETED)
            }
        } catch (e: Throwable) {
            sourceId?.let { id ->
                withContext(NonCancellable) {
                    try {
                        knowledgeDao.updateIndexStatus(id, IndexStatus.FAILED)
                    } catch (_: Exception) {
                        // Suppress secondary cleanup failure
                    }
                }
            }
            throw e
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun collectIndexableFiles(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        data class Entry(val file: DocumentFile, val depth: Int)
        val queue = ArrayDeque<Entry>()
        queue.addLast(Entry(root, 0))
        var visited = 0
        while (queue.isNotEmpty() && result.size < MAX_FILES && visited < MAX_VISITED) {
            val (current, depth) = queue.removeFirst()
            visited++
            if (depth >= MAX_DEPTH) continue
            for (child in current.listFiles()) {
                if (result.size >= MAX_FILES || visited >= MAX_VISITED) break
                when {
                    child.isDirectory -> queue.addLast(Entry(child, depth + 1))
                    child.isFile && isSupportedFile(child.name) -> result.add(child)
                }
            }
        }
        return result
    }

    private fun isSupportedFile(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".docx") || lower.endsWith(".pdf")
    }

    private fun readText(file: DocumentFile): String? {
        if (file.length() > MAX_FILE_SIZE_BYTES) {
            return null
        }
        return try {
            val lower = file.name?.lowercase() ?: ""
            applicationContext.contentResolver.openInputStream(file.uri)?.use { input ->
                when {
                    lower.endsWith(".docx") -> com.agentdesk.knowledge.parser.DocxTextExtractor.extractText(input)
                    lower.endsWith(".pdf")  -> com.agentdesk.knowledge.parser.PdfTextExtractor.extractText(input)
                    lower.endsWith(".md")   -> com.agentdesk.knowledge.parser.MarkdownTextExtractor.extractText(input)
                    else                    -> input.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        } catch (_: Exception) {
            null
        }
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

        const val MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L // 5MB limit to prevent OOM (BUG-024)
        private const val MAX_FILES = 50
        private const val MAX_DEPTH = 8
        private const val MAX_VISITED = 500
        private const val CHUNK_SIZE = 500
        private const val MIN_FREE_BYTES = 500L * 1024L * 1024L // >500MB free
    }
}