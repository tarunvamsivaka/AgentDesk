package com.agentdesk.core.common.model

/**
 * Represents the capability tier of the device.
 * The agent uses this to gate heavy features and adapt behavior gracefully.
 *
 * - EMERGENCY: critically low resources — only bare minimum UI
 * - LITE: constrained device — disable AI + indexing
 * - BALANCED: mid-range — limited AI, background indexing throttled
 * - FULL: capable device — all features enabled
 */
enum class DeviceTier {
    EMERGENCY,
    LITE,
    BALANCED,
    FULL
}
