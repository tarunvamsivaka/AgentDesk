package com.agentdesk.core.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * UserHabitEntity stores aggregated on-device interaction statistics by package name and hour of day.
 *
 * Used by the Ambient Habit Engine to surface proactive suggestions and contextually boost ambiguous
 * intents (e.g., morning calendar vs. evening alarm) while preserving 100% on-device privacy with zero cloud egress.
 */
@Entity(
    tableName = "user_habit_stats",
    indices = [
        Index(value = ["package_name", "hour_of_day"], unique = true),
        Index(value = ["hour_of_day", "interaction_count"])
    ]
)
data class UserHabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String = "",
    @ColumnInfo(name = "hour_of_day") val hourOfDay: Int, // 0..23
    @ColumnInfo(name = "interaction_count") val interactionCount: Int = 1,
    @ColumnInfo(name = "total_foreground_time_ms") val totalForegroundTimeMs: Long = 0L,
    @ColumnInfo(name = "associated_intent") val associatedIntent: String? = null,
    @ColumnInfo(name = "confidence_weight") val confidenceWeight: Float = 0.5f,
    @ColumnInfo(name = "last_observed_at") val lastObservedAt: Long = System.currentTimeMillis()
)
