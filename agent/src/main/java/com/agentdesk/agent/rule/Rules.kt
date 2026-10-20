package com.agentdesk.agent.rule

/**
 * Result from the RuleEngine after matching an input string against intent patterns.
 *
 * @param intentName Dot-separated intent identifier (e.g., "system.set_alarm").
 * @param entities   Extracted parameters keyed by slot name.
 * @param confidence Match confidence — 1.0 for full regex match, 0.7 for partial/keyword match.
 */
data class RuleResult(
    val intentName: String,
    val entities: Map<String, String>,
    val confidence: Float
)

/**
 * Compile-time constants for all supported intent identifiers.
 * These strings are the single source of truth used by RuleEngine, PolicyEngine, and ToolExecutor.
 */
object Intents {
    const val SET_ALARM       = "system.set_alarm"
    const val SET_TIMER       = "system.set_timer"
    const val NOTE_CREATE     = "note.create"
    const val NOTE_APPEND     = "note.append"
    const val NOTE_SEARCH     = "note.search"
    const val APP_OPEN        = "app.open"
    const val SETTINGS_OPEN   = "system.open_settings"
    const val CALENDAR_CREATE = "calendar.create_event"
    const val SMS_DRAFT       = "message.draft_sms"
    const val EMAIL_DRAFT     = "message.draft_email"
    const val LIBRARY_SEARCH  = "library.search"
    const val DIAL_NUMBER     = "phone.dial"
    const val WEB_SEARCH      = "web.search"
    const val NAVIGATE_MAP    = "maps.navigate"
    const val DEVICE_HEALTH   = "device.health"
}
