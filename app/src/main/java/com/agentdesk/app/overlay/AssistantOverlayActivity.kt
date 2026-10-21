package com.agentdesk.app.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentdesk.agent.command.Command
import com.agentdesk.agent.command.CommandGateway
import com.agentdesk.agent.command.CommandOutcome
import com.agentdesk.app.MainActivity
import com.agentdesk.app.shortcut.ShortcutHelper
import com.agentdesk.app.ui.theme.AgentDeskTheme
import com.agentdesk.app.voice.OfflineVoiceController
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.security.AppLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AssistantOverlayActivity — Translucent floating assistant bottom-sheet.
 *
 * Catches [android.intent.action.ASSIST] without forcing a jarring full-screen
 * context switch over the foreground application.
 *
 * Supports typed and 100% offline voice command execution with immediate feedback,
 * while maintaining Zero Cloud Egress.
 */
@AndroidEntryPoint
class AssistantOverlayActivity : ComponentActivity() {

    @Inject
    lateinit var commandGateway: CommandGateway

    @Inject
    lateinit var voiceController: OfflineVoiceController

    @Inject
    lateinit var appLockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check app lock privacy flag
        lifecycleScope.launch {
            if (appLockManager.isLockEnabledAsync()) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            }
        }

        val autoStartVoice = intent?.action == ShortcutHelper.ACTION_VOICE_COMMAND

        setContent {
            AgentDeskTheme {
                AssistantOverlayContent(
                    commandGateway = commandGateway,
                    voiceController = voiceController,
                    autoStartVoice = autoStartVoice,
                    onDismiss = { finish() },
                    onOpenFullChat = { prompt ->
                        val chatIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("EXTRA_INITIAL_PROMPT", prompt)
                        }
                        startActivity(chatIntent)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceController.destroy()
    }
}

@Composable
private fun AssistantOverlayContent(
    commandGateway: CommandGateway,
    voiceController: OfflineVoiceController,
    autoStartVoice: Boolean,
    onDismiss: () -> Unit,
    onOpenFullChat: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var promptInput by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    var lastOutcomeMessage by remember { mutableStateOf<String?>(null) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            voiceController.startListening()
        }
    }

    val voiceState by voiceController.voiceState.collectAsState()

    // Handle incoming voice recognition states
    LaunchedEffect(voiceState) {
        when (val state = voiceState) {
            is OfflineVoiceController.VoiceState.Partial -> {
                promptInput = state.text
            }
            is OfflineVoiceController.VoiceState.Result -> {
                promptInput = state.text
                voiceController.cancel()
            }
            is OfflineVoiceController.VoiceState.Error -> {
                lastOutcomeMessage = state.message
            }
            else -> {}
        }
    }

    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) {
            if (hasMicPermission) {
                voiceController.startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val suggestionChips = listOf(
        "Take a quick note",
        "Set alarm 7 AM",
        "Device health stats",
        "Search documents"
    )

    // Outer Scrim: Tapping outside dismisses overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Inner Bottom Sheet Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Intercept clicks inside card */ }
                ),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "AgentDesk",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF22C55E).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Zero-Cloud",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF22C55E)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close overlay",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Suggestion chips
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(suggestionChips) { chipText ->
                        SuggestionChip(
                            onClick = {
                                promptInput = chipText
                            },
                            label = { Text(chipText, fontSize = 12.sp) }
                        )
                    }
                }

                // Voice Listening Feedback
                AnimatedVisibility(visible = voiceState is OfflineVoiceController.VoiceState.Listening) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Listening offline...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Command Input Field
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask or execute command...") },
                    singleLine = true,
                    leadingIcon = {
                        val isListening = voiceState is OfflineVoiceController.VoiceState.Listening
                        IconButton(
                            onClick = {
                                if (isListening) {
                                    voiceController.stopListening()
                                } else {
                                    if (hasMicPermission) {
                                        voiceController.startListening()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Voice Dictation",
                                tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val trimmed = promptInput.trim()
                                if (trimmed.isNotEmpty() && !isExecuting) {
                                    isExecuting = true
                                    lastOutcomeMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val outcome = commandGateway.submitText(trimmed, CommandSource.USER_CHAT)
                                            lastOutcomeMessage = when (outcome) {
                                                is CommandOutcome.Executed -> outcome.message
                                                is CommandOutcome.Rejected -> "Blocked by policy: ${outcome.reason}"
                                                is CommandOutcome.NeedsUserConfirmation -> "Action requires confirmation: ${outcome.prompt}"
                                                is CommandOutcome.Unknown -> "I didn't recognize that command."
                                                is CommandOutcome.Clarification -> "Please clarify your request."
                                            }
                                        } catch (e: Exception) {
                                            lastOutcomeMessage = "Error: ${e.message}"
                                        } finally {
                                            isExecuting = false
                                        }
                                    }
                                }
                            },
                            enabled = promptInput.isNotBlank() && !isExecuting
                        ) {
                            if (isExecuting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send Command"
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                // Result or open full chat banner
                AnimatedVisibility(visible = lastOutcomeMessage != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = lastOutcomeMessage ?: "",
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { onOpenFullChat(promptInput) }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open in full app",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
