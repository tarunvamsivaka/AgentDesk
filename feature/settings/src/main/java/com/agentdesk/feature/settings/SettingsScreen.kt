package com.agentdesk.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentdesk.core.settings.ConsentKeys

/** Consent keys surfaced as switches. Cloud Sync is excluded — it is permanently DISABLED (non-negotiable constraint). */
private val CONSENT_ITEMS = listOf(
    ConsentKeys.LOCAL_KNOWLEDGE_INDEXING to "Local knowledge indexing",
    ConsentKeys.SHARE_BRIDGE to "Share bridge",
    ConsentKeys.DEVICE_HEALTH_MONITORING to "Device health monitoring",
    ConsentKeys.AUDIT_LOG to "Audit log",
    ConsentKeys.APP_LOCK to "App lock",
    ConsentKeys.NOTIFICATIONS to "Notifications",
    ConsentKeys.CALENDAR_ACCESS to "Calendar access",
    ConsentKeys.CONTACTS_ACCESS to "Contacts access"
)

/**
 * Settings screen — real wired controls:
 * - App lock toggle (PIN) via AppLockManager
 * - Consent switches persisted via ConsentDao
 * - Inline expandable Audit Log
 * - "Delete all local data" behind a REQUIRED confirmation dialog
 * - Cloud Sync permanently shown as DISABLED (non-negotiable constraint)
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
    val consents by viewModel.consents.collectAsState(initial = emptyList())

    var showPinDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var auditExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // App lock — real toggle via AppLockManager
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "App Lock", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (appLockEnabled) "Enabled — PIN required to open AgentDesk"
                        else "Require a PIN to open AgentDesk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = appLockEnabled,
                    onCheckedChange = { checked ->
                        if (checked) showPinDialog = true else viewModel.disableAppLock()
                    }
                )
            }
        }

        // Consent dashboard — real switches persisted via ConsentDao
        Text("Consent Dashboard", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                CONSENT_ITEMS.forEach { (key, label) ->
                    val record = consents.firstOrNull { it.consentKey == key }
                    val granted = record?.state == com.agentdesk.core.common.model.ConsentState.GRANTED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = when (record?.state) {
                                    com.agentdesk.core.common.model.ConsentState.GRANTED -> "Granted"
                                    com.agentdesk.core.common.model.ConsentState.REVOKED -> "Revoked"
                                    else -> "Not set"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = granted,
                            onCheckedChange = { checked -> viewModel.setConsent(key, checked) }
                        )
                    }
                }
            }
        }

        // Audit log — inline expandable list
        Card(
            onClick = { auditExpanded = !auditExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Audit Log", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "See every action AgentDesk has taken on your behalf",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (auditExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (auditExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (auditExpanded) {
            AuditScreen()
        }

        // Delete all local data — destructive, confirmation REQUIRED
        Button(
            onClick = { showDeleteDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Delete all local data")
        }

        // Cloud sync — permanently disabled in MVP (non-negotiable constraint)
        CloudSyncCard()
    }

    if (showPinDialog) {
        PinDialog(
            onConfirm = { pin ->
                viewModel.enableAppLock(pin)
                showPinDialog = false
            },
            onDismiss = { showPinDialog = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete all local data?") },
            text = {
                Text("This permanently clears your notes, indexed documents, and the audit log from this device. This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAllLocalData()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Delete everything") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PinDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set App Lock PIN") },
        text = {
            Column {
                Text("Choose a PIN of at least 4 characters.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    placeholder = { Text("PIN") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin) },
                enabled = pin.length >= 4
            ) { Text("Enable") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CloudSyncCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cloud Sync",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Disabled — AgentDesk is local-first. No data leaves your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}