package com.agentdesk.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Settings screen — Consent Dashboard, Permission Center, Audit Log, App Lock, Cloud Sync.
 *
 * MVP: Navigation stubs only. Each card navigates to a dedicated sub-screen post-MVP.
 * Cloud sync is permanently shown as "Disabled" per non-negotiable constraints.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onConsentClicked: () -> Unit = {},
    onPermissionsClicked: () -> Unit = {},
    onAuditLogClicked: () -> Unit = {},
    onAppLockClicked: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        SettingsCard(
            icon = Icons.Default.PrivacyTip,
            title = "Consent Dashboard",
            subtitle = "Manage what AgentDesk is allowed to access",
            onClick = onConsentClicked
        )

        SettingsCard(
            icon = Icons.Default.Security,
            title = "Permission Center",
            subtitle = "Review and revoke granted OS permissions",
            onClick = onPermissionsClicked
        )

        SettingsCard(
            icon = Icons.Default.History,
            title = "Audit Log",
            subtitle = "See every action AgentDesk has taken on your behalf",
            onClick = onAuditLogClicked
        )

        SettingsCard(
            icon = Icons.Default.Lock,
            title = "App Lock",
            subtitle = "Require biometric or PIN to open AgentDesk",
            onClick = onAppLockClicked
        )

        // Cloud sync — permanently disabled in MVP (non-negotiable constraint)
        CloudSyncCard()
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
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
