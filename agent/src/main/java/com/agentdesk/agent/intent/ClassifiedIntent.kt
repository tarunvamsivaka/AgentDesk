package com.agentdesk.agent.intent

/**
 * Structured classified intent representation matching ML Kit GenAI Prompt API / Google AICore
 * structured output patterns (@Generable / @Guide).
 *
 * @param intentName Dot-notated intent identifier (e.g. "system.set_alarm", "message.draft_sms", "unknown")
 * @param confidence Confidence score in [0.0..1.0]
 * @param entities Extracted slot parameters
 * @param requiresConfirmation Whether policy requires user confirmation
 * @param clarificationChips Clarification suggestions when classification is ambiguous or thermal throttled
 */
data class ClassifiedIntent(
    val intentName: String = "unknown",
    val confidence: Float = 0.0f,
    val entities: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val clarificationChips: List<String> = emptyList()
)
