package com.agentdesk.app.share

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.agentdesk.knowledge.handler.SharedItemHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ShareReceiverActivity — handles content shared from other apps.
 *
 * This is a one-shot, no-UI activity:
 * 1. Receives the ACTION_SEND intent.
 * 2. Routes the shared content to SharedItemHandler in :knowledge.
 * 3. Finishes immediately (user never sees this activity).
 *
 * Privacy: shared content is stored locally only. No network calls.
 * Policy: no silent actions. Content is surfaced in the Library screen for user review.
 */
@AndroidEntryPoint
class ShareReceiverActivity : FragmentActivity() {

    @Inject
    lateinit var sharedItemHandler: SharedItemHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        if (action == Intent.ACTION_SEND) {
            scope.launch {
                try {
                    sharedItemHandler.handle(intent)
                } catch (e: Exception) {
                    // Non-fatal: shared item ingestion is best-effort
                }
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
