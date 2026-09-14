package com.agentdesk.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AuditScreen — lazy list of every action AgentDesk has taken (time, event
 * type, target, risk level) plus the actions the PolicyEngine blocked.
 * Read-only; the audit log is local-only per privacy constraints.
 */
@Composable
fun AuditScreen(
    modifier: Modifier = Modifier,
    viewModel: AuditViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsState(initial = emptyList())
    val rejected by viewModel.rejected.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (events.isEmpty()) {
            item {
                Text(
                    text = "No audit events yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(events, key = { it.id }) { event ->
            AuditRow(event)
        }
        if (rejected.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Blocked by policy engine", style = MaterialTheme.typography.titleSmall)
            }
            items(rejected, key = { it.id }) { decision ->
                RejectedRow(decision)
            }
        }
    }
}

@Composable
private fun AuditRow(event: AuditEventEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = event.eventType,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = event.riskLevel.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = event.targetId.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDate(event.occurredAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun RejectedRow(decision: PolicyDecisionEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = decision.intentName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = decision.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// Per-call Locale: SimpleDateFormat is created inside formatDate() so runtime
// locale changes are reflected (ConstantLocale lint fix)
private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))