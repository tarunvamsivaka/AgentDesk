package com.agentdesk.core.common.model

/**
 * Risk classification for commands and tool invocations.
 * PolicyEngine uses this to determine if confirmation is required.
 *
 * - NONE: informational, read-only, fully safe
 * - LOW: minor write with no external effect (e.g., set alarm)
 * - MEDIUM: moderate effect with reversible outcome (e.g., create note)
 * - HIGH: significant external effect requiring user confirmation (e.g., send draft)
 * - CRITICAL: destructive or irreversible — always blocked unless explicitly confirmed
 */
enum class RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
