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

    fun evaluate(normalizedText: String): RuleResult? {
        for ((intentName, pattern, extractor) in RULES) {
            val match = pattern.find(normalizedText) ?: continue
            val entities = extractor(match)
            val confidence = if (match.value.length.toFloat() / normalizedText.length > 0.6f) 1.0f else 0.7f
            return RuleResult(intentName, entities, confidence)
        }
        // Keyword fallback (partial match at 0.7)
        for ((intentName, keyword) in KEYWORD_FALLBACKS) {
            if (normalizedText.contains(keyword)) {
                return RuleResult(intentName, emptyMap(), 0.7f)
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
                intentName = Intents.NOTE_CREATE,
                pattern = Regex("""(?:note|write|jot|remember)\s+(?:down\s+)?(.+)"""),
                extractor = { m -> mapOf("text" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.APP_OPEN,
                pattern = Regex("""(?:open|launch|start)\s+(.+)"""),
                extractor = { m -> mapOf("appName" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.CALENDAR_CREATE,
                pattern = Regex("""(?:add|create|schedule)\s+(?:a\s+)?(?:meeting|event|appointment)(?:\s+(.+))?"""),
                extractor = { m -> mapOf("title" to m.groupValues[1].trim()) }
            ),
            Rule(
                intentName = Intents.SMS_DRAFT,
                pattern = Regex("""(?:text|message|sms)\s+(.+?)\s+(?:saying|that|:)\s+(.+)"""),
                extractor = { m -> mapOf("contact" to m.groupValues[1].trim(), "body" to m.groupValues[2].trim()) }
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
