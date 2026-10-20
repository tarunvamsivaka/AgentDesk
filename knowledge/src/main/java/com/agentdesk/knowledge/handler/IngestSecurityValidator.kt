package com.agentdesk.knowledge.handler

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Validates, quarantines, and checks magic bytes for incoming shared and imported content.
 *
 * Confused Deputy & Traversal Prevention:
 * - Rejects file:// URIs
 * - Rejects canonical paths inside application private directories (dataDir, databases, shared_prefs, files)
 * - Quarantines content:// into cacheDir/ingest_quarantine/ with a hard 25 MB byte limit
 * - Verifies magic byte headers: PK\x03\x04 (DOCX), %PDF- (PDF), or valid UTF-8 (MD/text)
 */
object IngestSecurityValidator {

    const val MAX_INGEST_BYTES = 25L * 1024L * 1024L // 25 MB
    val DOCX_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Checks if a URI is safe to ingest.
     * Rejects file:// URIs and any URI pointing to private app storage.
     */
    fun isUriSafe(context: Context? = null, uri: Uri?): Boolean {
        if (uri == null) return false
        val uriStr = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: uriStr.substringBefore(":", "").lowercase()
        val path = uri.path.orEmpty().ifEmpty { uriStr.substringAfter("://", "") }
        return isUriSafeInternal(context, scheme, path)
    }

    fun isUriSafe(uri: Uri?): Boolean = isUriSafe(null, uri)

    fun isUriStringSafe(context: Context? = null, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val scheme = uriString.substringBefore(":", "").lowercase()
        val path = uriString.substringAfter("://", "")
        return isUriSafeInternal(context, scheme, path)
    }

    private fun isUriSafeInternal(context: Context?, scheme: String, path: String): Boolean {
        // 1. Hard reject file:// schemes
        if (scheme == "file") {
            return false
        }

        // 2. Reject path traversal
        if (path.contains("..")) {
            return false
        }

        // 3. Reject access to app's private data directories
        val privateDataDir = context?.let { ctx ->
            try {
                File(ctx.applicationInfo?.dataDir ?: "").canonicalPath
            } catch (_: Exception) {
                ctx.filesDir?.parentFile?.canonicalPath.orEmpty()
            }
        }.orEmpty()

        val lowerPath = path.lowercase()
        if (privateDataDir.isNotEmpty()) {
            val lowerDataDir = privateDataDir.lowercase()
            if (lowerPath.startsWith(lowerDataDir)) {
                return false
            }
        }

        if (lowerPath.contains("/databases") ||
            lowerPath.contains("/shared_prefs") ||
            lowerPath.contains("/app_webview") ||
            lowerPath.contains("/no_backup")
        ) {
            return false
        }

        return true
    }

    /**
     * Quarantines stream payload from URI into cacheDir/ingest_quarantine/ with a 25 MB limit,
     * and validates magic bytes before consumption.
     */
    fun quarantineAndValidate(context: Context, uri: Uri, mimeType: String? = null): File? {
        if (!isUriSafe(context, uri)) {
            return null
        }

        val quarantineDir = File(context.cacheDir, "ingest_quarantine").apply { mkdirs() }
        val tempFile = File.createTempFile("quarantine_", ".tmp", quarantineDir)

        return try {
            var totalBytes = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read != -1) {
                        totalBytes += read
                        if (totalBytes > MAX_INGEST_BYTES) {
                            tempFile.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            } ?: run {
                tempFile.delete()
                return null
            }

            if (!verifyMagicBytes(tempFile, mimeType)) {
                tempFile.delete()
                return null
            }

            tempFile
        } catch (_: Exception) {
            tempFile.delete()
            null
        }
    }

    /**
     * Verifies file magic bytes:
     * - DOCX: PK\x03\x04
     * - PDF: %PDF-
     * - UTF-8: valid UTF-8 text / markdown without binary NUL bytes
     */
    fun verifyMagicBytes(file: File, mimeType: String? = null): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        val header = ByteArray(8)
        val readBytes = file.inputStream().use { it.read(header) }
        if (readBytes < 4) return false

        // Check DOCX magic (PK\x03\x04)
        if (header[0] == DOCX_MAGIC[0] && header[1] == DOCX_MAGIC[1] &&
            header[2] == DOCX_MAGIC[2] && header[3] == DOCX_MAGIC[3]) {
            return true
        }

        // Check PDF magic (%PDF-)
        if (readBytes >= 5 && header.copyOf(5).contentEquals(PDF_MAGIC)) {
            return true
        }

        // Check UTF-8 validity for text / markdown
        return isValidUtf8(file)
    }

    private fun isValidUtf8(file: File): Boolean {
        return try {
            val sampleSize = minOf(file.length(), 65536L).toInt()
            val sample = ByteArray(sampleSize)
            file.inputStream().use { it.read(sample) }

            // Check for NUL bytes which indicate binary data (not valid text/md)
            var nullCount = 0
            for (b in sample) {
                if (b == 0.toByte()) nullCount++
            }
            if (nullCount > 2) return false

            val decoder = StandardCharsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(sample))
            true
        } catch (_: Exception) {
            false
        }
    }
}
