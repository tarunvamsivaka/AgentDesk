package com.agentdesk.app.e2e.harnesses

import com.agentdesk.agent.rule.Intents

/**
 * Universal Mock AICore test harness for CI and emulator execution (Features F15, F16, F17, F18).
 * Simulates on-device Gemini Nano Prompt API / Google AICore behavior with structured output schemas
 * and prompt prefix caching.
 */
object MockAiCoreHarness {

    /**
     * Static persona and tool schema prompt prefix exceeding 600 tokens
     * designed for KV cache pre-compilation and acceleration (Feature F16).
     */
    val STATIC_PROMPT_PREFIX: String = buildString {
        append("You are AgentDesk, an adaptive, privacy-first, on-device AI copilot for Android operating with zero cloud egress. ")
        append("All actions execute locally using on-device tools and Room database persistence. ")
        append("Never suggest or attempt network communication, cloud synchronization, or remote telemetry. ")
        append("You must analyze user natural language commands and classify them into exactly one of the supported intents with typed parameters.\n\n")

        append("### CORE TOOL CATALOGUE & SCHEMA DEFINITIONS:\n")
        append("1. Intent: ").append(Intents.SET_ALARM).append("\n")
        append("   Description: Sets a system alarm clock for a specified hour and minute.\n")
        append("   Parameters: hour (integer 0-23), minute (integer 0-59), message (string, optional).\n\n")

        append("2. Intent: ").append(Intents.SET_TIMER).append("\n")
        append("   Description: Sets a countdown timer for a duration in seconds.\n")
        append("   Parameters: length (integer in seconds), message (string, optional).\n\n")

        append("3. Intent: ").append(Intents.NOTE_CREATE).append("\n")
        append("   Description: Creates and persists a secure local note.\n")
        append("   Parameters: text (string, content of note), title (string, optional).\n\n")

        append("4. Intent: ").append(Intents.APP_OPEN).append("\n")
        append("   Description: Launches an installed application via package name or label.\n")
        append("   Parameters: package_name (string), app_name (string).\n\n")

        append("5. Intent: ").append(Intents.CALENDAR_CREATE).append("\n")
        append("   Description: Schedules a new event in the local device calendar.\n")
        append("   Parameters: title (string), start_time (epoch millis or ISO string), description (string, optional).\n\n")

        append("6. Intent: ").append(Intents.SMS_DRAFT).append("\n")
        append("   Description: Opens the transparent native SMS application with pre-filled recipient and text.\n")
        append("   Parameters: contact (string), message (string).\n")
        append("   Security: Medium risk, requires explicit user confirmation before opening system UI.\n\n")

        append("7. Intent: ").append(Intents.LIBRARY_SEARCH).append("\n")
        append("   Description: Performs local hybrid dense vector and FTS4 semantic retrieval in the knowledge vault.\n")
        append("   Parameters: query (string), max_results (integer, default 10).\n\n")

        append("8. Intent: ").append(Intents.DIAL_NUMBER).append("\n")
        append("   Description: Opens the system telephone dialer with a specified contact or phone number.\n")
        append("   Parameters: contact (string), phone_number (string).\n")
        append("   Security: Medium risk, requires explicit user confirmation before opening system UI.\n\n")

        append("9. Intent: ").append(Intents.WEB_SEARCH).append("\n")
        append("   Description: Opens the local web browser to search for a query.\n")
        append("   Parameters: query (string).\n\n")

        append("10. Intent: ").append(Intents.NAVIGATE_MAP).append("\n")
        append("    Description: Opens device mapping application for geographic directions.\n")
        append("    Parameters: destination (string).\n\n")

        append("11. Intent: ").append(Intents.DEVICE_HEALTH).append("\n")
        append("    Description: Queries local battery level, storage capacity, and thermal governance status.\n")
        append("    Parameters: none.\n\n")

        append("### NON-NEGOTIABLE SAFETY & FORBIDDEN INTENTS POLICY:\n")
        append("The following actions are strictly FORBIDDEN and must NEVER be classified or permitted under any circumstances: ")
        append("message.send_sms_silent, message.send_whatsapp_silent, call.place_silent, app.install_silent, ")
        append("file.delete_silent, accessibility.automate, sms.read_background, call_log.read, scrape.third_party_app, cloud.sync_silent.\n")
        append("If the user requests any forbidden action, immediately report rejection.\n")
    }

    /**
     * Estimated token count of the static prompt prefix (1 token ~ 4 characters).
     */
    fun estimateTokenCount(text: String): Int = text.length / 4

    /**
     * Structured ClassifiedIntent representation matching `@Generable` schema.
     */
    data class MockClassifiedIntent(
        val intentName: String,
        val confidence: Float,
        val entities: Map<String, String> = emptyMap(),
        val requiresConfirmation: Boolean = false,
        val clarificationChips: List<String> = emptyList()
    )

    /**
     * Deterministic simulation of Gemini Nano on-device classification with 4-tier fallback pyramid.
     */
    fun classify(query: String, thermalThrottled: Boolean = false): MockClassifiedIntent {
        val normalized = query.trim().lowercase()

        // Tier 3 Degradation: When thermal throttled or empty, return clarification chips
        if (thermalThrottled || normalized.isBlank()) {
            return MockClassifiedIntent(
                intentName = "clarification",
                confidence = 0.2f,
                clarificationChips = listOf("Search Library", "Create Note", "Check Device Health")
            )
        }

        // Tier 1 Simulation: Complex natural language semantic patterns
        return when {
            normalized.contains("wake me up") || normalized.contains("alarm tomorrow") ->
                MockClassifiedIntent(
                    intentName = Intents.SET_ALARM,
                    confidence = 0.95f,
                    entities = mapOf("hour" to "7", "minute" to "0")
                )

            normalized.contains("ping") || normalized.contains("text") || normalized.contains("send a message") ->
                MockClassifiedIntent(
                    intentName = Intents.SMS_DRAFT,
                    confidence = 0.92f,
                    entities = mapOf("contact" to "John", "message" to "Meeting at 5"),
                    requiresConfirmation = true
                )

            normalized.contains("look up") || normalized.contains("find in my documents") || normalized.contains("what does my note say") ->
                MockClassifiedIntent(
                    intentName = Intents.LIBRARY_SEARCH,
                    confidence = 0.90f,
                    entities = mapOf("query" to normalized)
                )

            normalized.contains("how hot is the phone") || normalized.contains("battery status") || normalized.contains("system status") ->
                MockClassifiedIntent(
                    intentName = Intents.DEVICE_HEALTH,
                    confidence = 0.98f
                )

            normalized.contains("call") || normalized.contains("dial") || normalized.contains("phone") ->
                MockClassifiedIntent(
                    intentName = Intents.DIAL_NUMBER,
                    confidence = 0.94f,
                    entities = mapOf("contact" to "Mom"),
                    requiresConfirmation = true
                )

            normalized.contains("sync to server") || normalized.contains("upload to cloud") || normalized.contains("backup to drive") ->
                MockClassifiedIntent(
                    intentName = "cloud.sync_silent",
                    confidence = 0.99f
                )

            else ->
                MockClassifiedIntent(
                    intentName = "unknown",
                    confidence = 0.3f,
                    clarificationChips = listOf("Search Library", "Set Alarm", "Create Note")
                )
        }
    }
}
