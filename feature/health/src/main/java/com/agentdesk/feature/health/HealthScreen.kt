package com.agentdesk.feature.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val THERMAL_LABELS = listOf(
    "None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown"
)

/**
 * Health screen — real device metrics: storage (StatFs), battery
 * (BatteryManager), thermal status (PowerManager), tier badge from the
 * latest DeviceCapabilitySnapshotEntity.
 */
@Composable
fun HealthScreen(
    modifier: Modifier = Modifier,
    viewModel: HealthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Device Health",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        val totalGb = state.totalStorageMb / 1024.0
        val freeGb = state.freeStorageMb / 1024.0
        val usedFraction = if (state.totalStorageMb > 0) {
            ((state.totalStorageMb - state.freeStorageMb).toFloat() / state.totalStorageMb)
                .coerceIn(0f, 1f)
        } else 0f

        HealthCard(
            icon = Icons.Default.Storage,
            title = "Storage",
            detail = "%.1f GB used of %.1f GB — %.1f GB free".format(totalGb - freeGb, totalGb, freeGb),
            progress = usedFraction
        )

        HealthCard(
            icon = Icons.Default.Battery5Bar,
            title = "Battery",
            detail = if (state.isCharging) "${state.batteryPercent}% — Charging" else "${state.batteryPercent}%",
            progress = (state.batteryPercent / 100f).coerceIn(0f, 1f)
        )

        TierCard(
            tier = state.deviceTier?.name ?: "UNKNOWN",
            thermalLabel = THERMAL_LABELS.getOrElse(state.thermalStatus) { "Unknown" }
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Metrics are read live from this device. Nothing is uploaded.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun HealthCard(
    icon: ImageVector,
    title: String,
    detail: String,
    progress: Float
) {
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
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TierCard(tier: String, thermalLabel: String) {
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
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Device Tier", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Thermal status: $thermalLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = tier,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}