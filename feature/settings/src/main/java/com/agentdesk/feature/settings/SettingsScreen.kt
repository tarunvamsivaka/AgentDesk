package com.agentdesk.feature.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentdesk.agent.rule.RuleEngine
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
 * - Authenticated AES-256-GCM Encrypted Vault Backup & Restore (.agentdesk) via SAF
 * - Custom User Rules Builder for keyword-triggered intent automations
 * - "Delete all local data" behind a REQUIRED confirmation dialog
 * - Cloud Sync permanently shown as DISABLED (non-negotiable constraint)
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
    val consents by viewModel.consents.collectAsState(initial = emptyList())
    val customRules by viewModel.customRules.collectAsState()
    val vaultStatusMessage by viewModel.vaultStatusMessage.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setConsent(ConsentKeys.NOTIFICATIONS, isGranted)
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setConsent(ConsentKeys.CONTACTS_ACCESS, isGranted)
    }

    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showExportPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showImportPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showAddRuleDialog by rememberSaveable { mutableStateOf(false) }
    var auditExpanded by rememberSaveable { mutableStateOf(false) }

    var exportPendingPassword by rememberSaveable { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && exportPendingPassword.isNotBlank()) {
            context.contentResolver.openOutputStream(uri)?.let { stream ->
                viewModel.exportVault(exportPendingPassword, stream) { _, _ -> }
            }
            exportPendingPassword = ""
        }
    }

    val importVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportPasswordDialog = true
        }
    }

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

        // Status banner
        if (vaultStatusMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = vaultStatusMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearVaultStatusMessage() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }

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

        // Encrypted Vault Backup & Restore
        Text("Encrypted Vault Backup", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Offline Encrypted Backup (.agentdesk)", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Export your private notes, custom rules, and history encrypted with AES-256-GCM and PBKDF2 (100k rounds). Zero cloud egress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showExportPasswordDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export Vault")
                    }
                    OutlinedButton(
                        onClick = { importVaultLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore Vault")
                    }
                }
            }
        }

        // Custom User Rules Builder
        Text("Custom Rules", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Keyword Trigger Automations", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showAddRuleDialog = true }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Add Rule", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (customRules.isEmpty()) {
                    Text(
                        text = "No custom rules defined yet. Add keywords like 'flight status' to automatically launch your travel app or trigger tools.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    customRules.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "\"${rule.triggerPhrase}\"", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "-> ${rule.intentName}${if (rule.targetAppOrPackage != null) " (${rule.targetAppOrPackage})" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(onClick = { viewModel.removeCustomRule(rule.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Rule", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
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
                            onCheckedChange = { checked ->
                                if (checked) {
                                    when (key) {
                                        ConsentKeys.NOTIFICATIONS -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                viewModel.setConsent(key, true)
                                            }
                                        }
                                        ConsentKeys.CONTACTS_ACCESS -> {
                                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                        }
                                        else -> viewModel.setConsent(key, true)
                                    }
                                } else {
                                    viewModel.setConsent(key, false)
                                }
                            }
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

    if (showExportPasswordDialog) {
        VaultPasswordDialog(
            title = "Export Encrypted Vault",
            confirmText = "Continue",
            onConfirm = { pwd ->
                exportPendingPassword = pwd
                showExportPasswordDialog = false
                exportVaultLauncher.launch("agentdesk_backup_${System.currentTimeMillis()}.agentdesk")
            },
            onDismiss = { showExportPasswordDialog = false }
        )
    }

    if (showImportPasswordDialog) {
        VaultPasswordDialog(
            title = "Restore Encrypted Vault",
            confirmText = "Decrypt & Restore",
            onConfirm = { pwd ->
                val uri = pendingImportUri
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.let { stream ->
                        viewModel.importVault(pwd, stream) { _, _ -> }
                    }
                    pendingImportUri = null
                }
                showImportPasswordDialog = false
            },
            onDismiss = {
                showImportPasswordDialog = false
                pendingImportUri = null
            }
        )
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            onConfirm = { trigger, intent, targetApp ->
                viewModel.addCustomRule(trigger, intent, targetApp)
                showAddRuleDialog = false
            },
            onDismiss = { showAddRuleDialog = false }
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
private fun VaultPasswordDialog(
    title: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Enter the vault master encryption password (AES-256-GCM).")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.length >= 6
            ) { Text(confirmText) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddRuleDialog(
    onConfirm: (String, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var trigger by rememberSaveable { mutableStateOf("") }
    var intent by rememberSaveable { mutableStateOf("app.open") }
    var targetApp by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("When I say... (Keyword)") },
                    placeholder = { Text("e.g. flight status") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = intent,
                    onValueChange = { intent = it },
                    label = { Text("Intent Action") },
                    placeholder = { Text("e.g. app.open or note.create") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = targetApp,
                    onValueChange = { targetApp = it },
                    label = { Text("Target App / Package (Optional)") },
                    placeholder = { Text("e.g. com.airline.app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trigger, intent, targetApp.ifBlank { null }) },
                enabled = trigger.isNotBlank() && intent.isNotBlank()
            ) { Text("Save Rule") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PinDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set App Lock PIN") },
        text = {
            Column {
                Text("Choose a PIN between 4 and 8 digits.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (pin.length >= 4) onConfirm(pin) }
                    ),
                    modifier = Modifier.fillMaxWidth()
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
