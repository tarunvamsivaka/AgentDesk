package com.agentdesk.core.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentdesk.core.persistence.entity.UserHabitEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for user habit statistics.
 */
@Dao
interface UserHabitDao {

    @Query("SELECT * FROM user_habit_stats WHERE hour_of_day = :hourOfDay ORDER BY interaction_count DESC LIMIT :limit")
    fun observeTopHabitsForHour(hourOfDay: Int, limit: Int = 5): Flow<List<UserHabitEntity>>

    @Query("SELECT * FROM user_habit_stats WHERE hour_of_day = :hourOfDay ORDER BY interaction_count DESC LIMIT :limit")
    suspend fun getTopHabitsForHour(hourOfDay: Int, limit: Int = 5): List<UserHabitEntity>

    @Query("SELECT * FROM user_habit_stats WHERE package_name = :packageName AND hour_of_day = :hourOfDay LIMIT 1")
    suspend fun getHabit(packageName: String, hourOfDay: Int): UserHabitEntity?

    @Query("SELECT * FROM user_habit_stats WHERE associated_intent = :intent AND hour_of_day = :hourOfDay LIMIT 1")
    suspend fun getHabitForIntent(intent: String, hourOfDay: Int): UserHabitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(habit: UserHabitEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(habits: List<UserHabitEntity>)

    @Query("DELETE FROM user_habit_stats")
    suspend fun deleteAll()
}
