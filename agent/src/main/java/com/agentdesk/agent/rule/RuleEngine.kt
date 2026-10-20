package com.agentdesk.agent.rule

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic, regex-based intent classifier.
 *
 * No LLM involved. Patterns are precompiled at object initialisation.
 * Returns null if no pattern matches above the threshold.
 */
@Singleton
class RuleEngine @Inject constructor() {

    data class CustomRule(
        val id: String = java.util.UUID.randomUUID().toString(),
        val triggerPhrase: String,
        val intentName: String,
        val targetAppOrPackage: String? = null,
        val defaultParameters: Map<String, String> = emptyMap()
    )

    private val customRules = java.util.concurrent.CopyOnWriteArrayList<CustomRule>()

    fun registerCustomRule(rule: CustomRule) {
        customRules.removeIf { it.id == rule.id || it.triggerPhrase.equals(rule.triggerPhrase, ignoreCase = true) }
        customRules.add(0, rule)
    }

    fun removeCustomRule(id: String) {
        customRules.removeIf { it.id == id }
    }

    fun getCustomRules(): List<CustomRule> = customRules.toList()

    fun clearCustomRules() {
        customRules.clear()
    }

    fun evaluate(normalizedText: String): RuleResult? {
        val searchTarget = if (normalizedText.length > 1000) normalizedText.substring(0, 1000) else normalizedText
        val lowerTarget = searchTarget.lowercase()

        // 1. Evaluate custom user rules with highest priority
        for (cr in customRules) {
            val trigger = cr.triggerPhrase.trim().lowercase()
            if (trigger.isNotEmpty() && lowerTarget.contains(trigger)) {
                val entities = cr.defaultParameters.toMutableMap()
                if (cr.targetAppOrPackage != null) {
                    entities["app"] = cr.targetAppOrPackage
                    entities["package"] = cr.targetAppOrPackage
                }
                return RuleResult(cr.intentName, entities, 1.0f)
            }
        }

        // 2. Evaluate precompiled pattern rules
        for ((intentName, pattern, extractor) in RULES) {
            val match = pattern.find(searchTarget) ?: continue
            val entities = extractor(match)
            val confidence = if (match.value.length.toFloat() / normalizedText.length > 0.6f) 1.0f else 0.7f
            return RuleResult(intentName, entities, confidence)
        }
        // 3. Keyword fallback (partial match at 0.7)
        for ((intentName, keyword) in KEYWORD_FALLBACKS) {
            if (searchTarget.contains(keyword)) {
                val entities = if (intentName == Intents.WEB_SEARCH) {
                    val query = searchTarget.substringAfter(keyword).trim().removePrefix("for").trim()
                    if (query.isNotBlank()) mapOf("query" to query) else emptyMap()
                } else {
                    emptyMap()
                }
                return RuleResult(intentName, entities, 0.7f)
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Rules table — (intentName, pattern, entity extractor)
    // ---------------------------------------------------------------------------
    private data class Rule(
        val intentName: String,
        val pattern: Regex,
        val extractor: (MatchResult) -> Map<String, String>
    )

    private companion object {
        private val RULES = listOf(
            Rule(
                intentName = Intents.SET_ALARM,
                pattern = Regex("""(?:set|wake me|alarm)\s+(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)"""),
                extractor = { m -> mapOf("time" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.SET_TIMER,
                pattern = Regex("""(?:set\s+a?\s*)?timer\s+(?:for\s+)?(\d+)\s*(minute|second|hour)s?"""),
                extractor = { m -> mapOf("duration" to m.groupValues[1], "unit" to m.groupValues[2]) }
            ),
            Rule(
                intentName = Intents.NOTE_APPEND,
                pattern = Regex("""(?:append|add)\s+(?:to\s+note|to\s+notes)\s+(.+)"""),
                extractor = { m -> mapOf("text" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.NOTE_SEARCH,
                pattern = Regex("""(?:search|find)\s+notes?\s+(?:for\s+)?(.+)"""),
                extractor = { m -> mapOf("query" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.NOTE_CREATE,
                pattern = Regex("""(?:create\s+note|take\s+note|note|write|jot|remember)\s+(?:down\s+)?(.+)"""),
                extractor = { m -> mapOf("text" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.CALENDAR_CREATE,
                pattern = Regex("""(?:add|create|schedule)\s+(?:a\s+)?(?:meeting|event|appointment)(?:\s+(?:called|titled|with\s+title)?\s*(.+))?"""),
                extractor = { m -> mapOf("title" to m.groupValues[1].trim().ifBlank { "New Event" }) }
            ),
            Rule(
                intentName = Intents.EMAIL_DRAFT,
                pattern = Regex("""(?:email|mail|send\s+email\s+to)\s+([^:\n\r]+?)\s+(?:saying|about|with\s+subject|that|:)\s+(.+)"""),
                extractor = { m -> mapOf("recipient" to m.groupValues[1].trim(), "body" to m.groupValues[2].trim()) }
            ),
            Rule(
                intentName = Intents.SMS_DRAFT,
                pattern = Regex("""(?:draft\s+)?(?:text|message|sms)(?:\s+to)?\s+([^:\n\r]+?)\s+(?:saying|that|:)\s+(.+)"""),
                extractor = { m -> mapOf("contact" to m.groupValues[1].trim(), "body" to m.groupValues[2].trim()) }
            ),
            Rule(
                intentName = Intents.SMS_DRAFT,
                pattern = Regex("""(?:draft\s+)?(?:text|message|sms)\s+to\s+([^:\n\r]+)"""),
                extractor = { m -> mapOf("contact" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.WEB_SEARCH,
                pattern = Regex("""(?:search\s+(?:the\s+)?(?:web|online|internet)\s+(?:for\s+)?|google\s+)(.+)"""),
                extractor = { m -> mapOf("query" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.DEVICE_HEALTH,
                pattern = Regex("""(?:check\s+)?(?:device\s+health|battery\s+(?:status|level|pct)|storage\s+(?:status|space|free))"""),
                extractor = { emptyMap() }
            ),
            Rule(
                intentName = Intents.LIBRARY_SEARCH,
                pattern = Regex("""(?:find|search|look for|where is)\s+(.+)"""),
                extractor = { m -> mapOf("query" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.DIAL_NUMBER,
                pattern = Regex("""(?:call|dial|ring)\s+(.+)"""),
                extractor = { m -> mapOf("contact" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.NAVIGATE_MAP,
                pattern = Regex("""(?:navigate|directions|take me)\s+(?:to\s+)?(.+)"""),
                extractor = { m -> mapOf("destination" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.SETTINGS_OPEN,
                pattern = Regex("""(?:open|show)\s+(?:the\s+)?(wifi|wi-fi|bluetooth|sound|audio|volume|display|screen|brightness|battery|power|storage|date)\s+settings?"""),
                extractor = { m -> mapOf("setting" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.SETTINGS_OPEN,
                pattern = Regex("""(?:open|show)\s+(?:system\s+)?settings(?:\s+(?:for\s+)?(.+))?"""),
                extractor = { m -> mapOf("setting" to (m.groupValues[1].takeIf { it.isNotBlank() }?.trim() ?: "all")) }
            ),
            Rule(
                intentName = Intents.APP_OPEN,
                pattern = Regex("""(?:open|launch|start)\s+(.+)"""),
                extractor = { m -> mapOf("appName" to m.groupValues[1].trim()) }
            )
        )

        private val KEYWORD_FALLBACKS = listOf(
            Intents.DEVICE_HEALTH to "battery",
            Intents.DEVICE_HEALTH to "storage",
            Intents.DEVICE_HEALTH to "health",
            Intents.WEB_SEARCH to "search online",
            Intents.WEB_SEARCH to "google",
        )
    }
}
