package com.agentdesk.agent.habit

import com.agentdesk.agent.intent.ClassifiedIntent
import com.agentdesk.agent.rule.Intents
import com.agentdesk.core.persistence.dao.UserHabitDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HabitPredictionEngine uses local user routine statistics to contextually boost ambiguous queries
 * and surface proactive suggestion chips based on time of day.
 *
 * Invariant:
 * Strictly on-device heuristics and local Room DB statistics. Zero cloud egress.
 * PolicyEngine always has final authority over any boosted or predicted intent.
 */
@Singleton
open class HabitPredictionEngine @Inject constructor(
    private val habitDao: UserHabitDao
) {

    /**
     * Contextually boosts intent classification for ambiguous queries based on time of day.
     *
     * Examples:
     * - Morning (07:00–09:59): "what's next", "show schedule", "my day" -> calendar.create_event
     * - Evening (21:00–23:59): "tomorrow", "wake me up", "bedtime" -> system.set_alarm
     *
     * @param query The user's raw or normalized text input.
     * @param currentHour The hour of day [0..23]. Defaults to current system time.
     * @return [ClassifiedIntent] with boosted confidence, or null if no habitual pattern matches.
     */
    open fun boostIntentIfHabitual(query: String, currentHour: Int = currentHourOfDay()): ClassifiedIntent? {
        val normalized = query.trim().lowercase()

        // Morning Calendar boosting for ambiguous temporal queries
        if (currentHour in 7..9) {
            if (normalized == "what's next" ||
                normalized == "what is next" ||
                normalized == "show schedule" ||
                normalized == "my day" ||
                normalized == "schedule"
            ) {
                return ClassifiedIntent(
                    intentName = Intents.CALENDAR_CREATE,
                    confidence = 0.88f,
                    entities = mapOf("action" to "view_today")
                )
            }
        }

        // Evening Alarm boosting for sleep/wake preparation
        if (currentHour in 21..23) {
            if (normalized == "tomorrow" ||
                normalized == "wake me up" ||
                normalized == "bedtime" ||
                normalized == "alarm tomorrow"
            ) {
                return ClassifiedIntent(
                    intentName = Intents.SET_ALARM,
                    confidence = 0.90f,
                    entities = mapOf("hour" to "7", "minute" to "0")
                )
            }
        }

        return null
    }

    /**
     * Observes reactive flow of proactive suggestion chips for the current or specified hour.
     */
    fun observeActiveSuggestions(hourOfDay: Int = currentHourOfDay()): Flow<List<String>> {
        return habitDao.observeTopHabitsForHour(hourOfDay).map { habits ->
            habits.mapNotNull { habit ->
                habit.associatedIntent ?: habit.appName.ifBlank { null }
            }
        }
    }

    /**
     * Synchronously queries top habit suggestions for the specified hour.
     */
    suspend fun getActiveSuggestions(hourOfDay: Int = currentHourOfDay(), limit: Int = 5): List<String> {
        val habits = habitDao.getTopHabitsForHour(hourOfDay, limit)
        return habits.mapNotNull { habit ->
            habit.associatedIntent ?: habit.appName.ifBlank { null }
        }
    }

    open fun currentHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
