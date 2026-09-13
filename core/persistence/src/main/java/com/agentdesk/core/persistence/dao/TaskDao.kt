package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.common.model.TaskStatus
import com.agentdesk.core.persistence.entity.TaskExecutionEntity
import com.agentdesk.core.persistence.entity.TaskStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM task_execution WHERE command_id = :commandId")
    suspend fun findByCommandId(commandId: Long): TaskExecutionEntity?

    @Query("SELECT * FROM task_execution WHERE status = :status ORDER BY started_at DESC")
    fun observeByStatus(status: TaskStatus): Flow<List<TaskExecutionEntity>>

    @Insert
    suspend fun insertTask(task: TaskExecutionEntity): Long

    @Update
    suspend fun updateTask(task: TaskExecutionEntity)

    @Insert
    suspend fun insertStep(step: TaskStepEntity): Long

    @Update
    suspend fun updateStep(step: TaskStepEntity)

    @Query("SELECT * FROM task_step WHERE task_id = :taskId ORDER BY step_index ASC")
    suspend fun stepsForTask(taskId: Long): List<TaskStepEntity>
}
