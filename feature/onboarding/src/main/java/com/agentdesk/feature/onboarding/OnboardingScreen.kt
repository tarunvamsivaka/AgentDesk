package com.agentdesk.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentdesk.core.common.model.DeviceTier

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.step) {
        if (uiState.step == OnboardingStep.DONE) onComplete()
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    OnboardingStep.PRIVACY_CONSENT -> PrivacyConsentStep(
                        isLoading = uiState.isLoading,
                        onAccept = { viewModel.acceptConsent() }
                    )
                    OnboardingStep.DEVICE_SCAN -> DeviceScanStep(progress = uiState.scanProgress)
                    OnboardingStep.TIER_DISPLAY -> TierDisplayStep(
                        tier = uiState.deviceTier ?: DeviceTier.BALANCED,
                        onContinue = { viewModel.confirmTierAndProceed() }
                    )
                    OnboardingStep.DONE -> {}
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 1: Privacy Consent
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PrivacyConsentStep(isLoading: Boolean, onAccept: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Text("AgentDesk", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            text = "Your private, local-first copilot for files, notes, actions, and device health.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrivacyPoint(Icons.Default.Lock, "All data stays on your device")
                PrivacyPoint(Icons.Default.WifiOff, "No cloud sync by default")
                PrivacyPoint(Icons.Default.RemoveRedEye, "Every AI action is auditable")
                PrivacyPoint(Icons.Default.NotInterested, "No silent messages or calls")
            }
        }
        Button(
            onClick = onAccept,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Accept & Continue")
            }
        }
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 2: Device Scan
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceScanStep(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text("Scanning Your Device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "AgentDesk adapts its behaviour to your device capabilities.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(
            "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 3: Tier Display
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TierDisplayStep(tier: DeviceTier, onContinue: () -> Unit) {
    val tierColor = when (tier) {
        DeviceTier.FULL      -> MaterialTheme.colorScheme.primary
        DeviceTier.BALANCED  -> MaterialTheme.colorScheme.secondary
        DeviceTier.LITE      -> MaterialTheme.colorScheme.tertiary
        DeviceTier.EMERGENCY -> MaterialTheme.colorScheme.error
    }
    val tierIcon = when (tier) {
        DeviceTier.FULL      -> Icons.Default.Star
        DeviceTier.BALANCED  -> Icons.Default.Settings
        DeviceTier.LITE      -> Icons.Default.BatteryAlert
        DeviceTier.EMERGENCY -> Icons.Default.Warning
    }
    val tierLabel = when (tier) {
        DeviceTier.FULL      -> "Full Mode"
        DeviceTier.BALANCED  -> "Balanced Mode"
        DeviceTier.LITE      -> "Lite Mode"
        DeviceTier.EMERGENCY -> "Emergency Mode"
    }
    val tierDesc = when (tier) {
        DeviceTier.FULL      -> "All features enabled. On-device AI, rich media, and full indexing."
        DeviceTier.BALANCED  -> "Most features enabled. AI suggestions with conservative resource usage."
        DeviceTier.LITE      -> "Core features only. Reduced indexing and no on-device LLM."
        DeviceTier.EMERGENCY -> "Very limited resources detected. Only essential actions are available."
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Icon(imageVector = tierIcon, contentDescription = null, tint = tierColor, modifier = Modifier.size(72.dp))
        Text(tierLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tierColor)
        Text(
            tierDesc,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Get Started")
        }
    }
}
