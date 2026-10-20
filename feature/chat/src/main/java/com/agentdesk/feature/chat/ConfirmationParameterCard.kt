package com.agentdesk.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentdesk.agent.command.Command
import com.agentdesk.agent.rule.Intents

/**
 * Confirmation dialog displaying an action prompt and a structured parameter preview card
 * with in-place parameter editing support.
 */
@Composable
fun ConfirmationDialog(
    pending: PendingConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onEntityUpdated: ((key: String, value: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Confirm ${actionTitleFor(pending.command.intentName)}",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = pending.prompt,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (pending.command.entities.isNotEmpty()) {
                    ConfirmationParameterCard(
                        command = pending.command,
                        onEntityUpdated = onEntityUpdated
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Yes, proceed")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

/**
 * Card rendering structured key-value pairs for all parameters in [Command.entities].
 * Supports inline editing when [onEntityUpdated] callback is provided.
 */
@Composable
fun ConfirmationParameterCard(
    command: Command,
    onEntityUpdated: ((key: String, value: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Parameters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            command.entities.forEach { (key, value) ->
                if (onEntityUpdated != null) {
                    var textValue by remember(key, value) { mutableStateOf(value) }
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = {
                            textValue = it
                            onEntityUpdated(key, it)
                        },
                        label = { Text(formatParamKey(key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatParamKey(key)}:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.4f)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }
            }
        }
    }
}

private fun formatParamKey(key: String): String = when (key) {
    "contact" -> "Contact / Recipient"
    "body" -> "Message Body"
    "time" -> "Time"
    "duration" -> "Duration"
    "unit" -> "Time Unit"
    "title" -> "Event Title"
    "text" -> "Note Text"
    "destination" -> "Destination"
    "query" -> "Search Query"
    "appName" -> "Application"
    else -> key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun actionTitleFor(intentName: String): String = when (intentName) {
    Intents.SMS_DRAFT -> "SMS Draft"
    Intents.DIAL_NUMBER -> "Phone Call"
    Intents.EMAIL_DRAFT -> "Email Draft"
    Intents.SETTINGS_OPEN -> "System Settings"
    Intents.NOTE_APPEND -> "Append Note"
    Intents.NOTE_SEARCH -> "Search Notes"
    Intents.CALENDAR_CREATE -> "Calendar Event"
    Intents.SET_ALARM -> "Set Alarm"
    Intents.SET_TIMER -> "Set Timer"
    Intents.NOTE_CREATE -> "Create Note"
    Intents.NAVIGATE_MAP -> "Navigation"
    Intents.WEB_SEARCH -> "Web Search"
    Intents.APP_OPEN -> "Open App"
    Intents.DEVICE_HEALTH -> "Device Health"
    Intents.LIBRARY_SEARCH -> "Library Search"
    else -> "Action"
}
