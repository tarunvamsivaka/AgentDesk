package com.agentdesk.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.agentdesk.knowledge.handler.IngestSecurityValidator
import com.agentdesk.knowledge.handler.SharedItemHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ShareReceiverActivity — handles content shared from other apps.
 *
 * This is a one-shot, no-UI activity:
 * 1. Receives the ACTION_SEND intent.
 * 2. Copies incoming stream to quarantine cache file so large PDF/DOCX shares never abort mid-copy.
 * 3. Dispatches the shared content using a NonCancellable coroutine scope to SharedItemHandler in :knowledge.
 * 4. Finishes immediately upon completion.
 *
 * Privacy: shared content is stored locally only. No network calls.
 * Policy: no silent actions. Content is surfaced in the Library screen for user review.
 */
@AndroidEntryPoint
class ShareReceiverActivity : FragmentActivity() {

    @Inject
    lateinit var sharedItemHandler: SharedItemHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        if (action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent?.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            // Security check: Reject file:// schemes and private storage path traversal
            if (streamUri != null && !IngestSecurityValidator.isUriSafe(this, streamUri)) {
                finish()
                return
            }

            val safeIntent = Intent(intent)
            scope.launch {
                try {
                    withContext(NonCancellable) {
                        if (streamUri != null) {
                            try {
                                val quarantineDir = File(cacheDir, "quarantine").apply { mkdirs() }
                                val tempFile = File(quarantineDir, "share_${System.currentTimeMillis()}.bin")
                                contentResolver.openInputStream(streamUri)?.use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                safeIntent.putExtra("EXTRA_QUARANTINE_PATH", tempFile.absolutePath)
                            } catch (_: Exception) {
                                // Fallback to direct stream uri if temp copy fails
                            }
                        }
                        sharedItemHandler.handle(safeIntent)
                    }
                } catch (e: Exception) {
                    // Non-fatal: shared item ingestion is best-effort
                } finally {
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
