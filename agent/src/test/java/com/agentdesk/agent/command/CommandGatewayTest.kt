package com.agentdesk.agent.command

import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.StepStatus
import com.agentdesk.core.common.model.TaskStatus
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.device.ResourceGovernor
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.dao.TaskDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import com.agentdesk.core.persistence.entity.TaskExecutionEntity
import com.agentdesk.core.persistence.entity.TaskStepEntity
import com.agentdesk.core.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

private data class StatusUpdate(
    val id: Long,
    val intentName: String,
    val status: String,
    val latencyMs: Long
)

private class FakeCommandDao : CommandDao {
    val inserted = mutableListOf<CommandRecordEntity>()
    val statusUpdates = mutableListOf<StatusUpdate>()

    override fun observeRecent(limit: Int): Flow<List<CommandRecordEntity>> = emptyFlow()
    override suspend fun findByThread(threadId: Long): List<CommandRecordEntity> = emptyList()
    override suspend fun insert(record: CommandRecordEntity): Long {
        inserted.add(record)
        return inserted.size.toLong()
    }
    override suspend fun updateResult(id: Long, intentName: String, status: String, latencyMs: Long) {
        statusUpdates.add(StatusUpdate(id, intentName, status, latencyMs))
    }
    override suspend fun deleteOlderThan(beforeEpoch: Long) {}
}

private class FakeAuditDao : AuditDao {
    val events = mutableListOf<AuditEventEntity>()

    override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> = emptyFlow()
    override fun observeByType(eventType: String): Flow<List<AuditEventEntity>> = emptyFlow()
    override suspend fun insert(event: AuditEventEntity): Long {
        events.add(event)
        return events.size.toLong()
    }
    override suspend fun deleteOlderThan(beforeEpoch: Long) {}
    override suspend fun getLatestEvent(): AuditEventEntity? = events.lastOrNull()
    override suspend fun getEventsBySequenceRange(startSeq: Long, endSeq: Long): List<AuditEventEntity> =
        events.filter { it.sequenceNumber in startSeq..endSeq }
    override suspend fun getEventCount(): Long = events.size.toLong()
    override suspend fun deleteAll() {
        events.clear()
    }
}

private class FakePolicyDecisionDao : PolicyDecisionDao {
    val decisions = mutableListOf<PolicyDecisionEntity>()

    override suspend fun insert(decision: PolicyDecisionEntity): Long {
        decisions.add(decision)
        return decisions.size.toLong()
    }
    override fun observeRecent(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeByIntent(intent: String, limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override fun observeRejected(limit: Int): Flow<List<PolicyDecisionEntity>> = emptyFlow()
    override suspend fun deleteOlderThan(beforeMs: Long) {}
    override suspend fun count(): Int = decisions.size
}

private class FakeToolRunner : ToolRunner {
    val invoked = mutableListOf<Pair<String, Map<String, String>>>()
    var nextResult: ToolRunResult = ToolRunResult("unknown", true, "ok")

    override suspend fun execute(
        toolId: String,
        parameters: Map<String, String>,
        commandId: Long
    ): ToolRunResult {
        invoked.add(toolId to parameters)
        return nextResult
    }
}

private class FakeTaskDao : TaskDao {
    val tasks = mutableListOf<TaskExecutionEntity>()
    val steps = mutableListOf<TaskStepEntity>()
    private var nextTaskId = 1L
    private var nextStepId = 1L

    override suspend fun findByCommandId(commandId: Long): TaskExecutionEntity? =
        tasks.firstOrNull { it.commandId == commandId }

    override suspend fun findById(taskId: Long): TaskExecutionEntity? =
        tasks.firstOrNull { it.id == taskId }

    override fun observeByStatus(status: TaskStatus): Flow<List<TaskExecutionEntity>> =
        flowOf(tasks.filter { it.status == status })

    override suspend fun getTasksByStatus(status: TaskStatus): List<TaskExecutionEntity> =
        tasks.filter { it.status == status }

    override suspend fun getIncompleteTasks(): List<TaskExecutionEntity> =
        tasks.filter { it.status in setOf(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.AWAITING_CONFIRMATION, TaskStatus.PAUSED) }

    override suspend fun insertTask(task: TaskExecutionEntity): Long {
        val id = if (task.id == 0L) nextTaskId++ else task.id
        val saved = task.copy(id = id)
        tasks.add(saved)
        return id
    }

    override suspend fun updateTask(task: TaskExecutionEntity) {
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) tasks[idx] = task
    }

    override suspend fun insertStep(step: TaskStepEntity): Long {
        val id = if (step.id == 0L) nextStepId++ else step.id
        val saved = step.copy(id = id)
        steps.add(saved)
        return id
    }

    override suspend fun updateStep(step: TaskStepEntity) {
        val idx = steps.indexOfFirst { it.id == step.id }
        if (idx >= 0) steps[idx] = step
    }

    override suspend fun stepsForTask(taskId: Long): List<TaskStepEntity> =
        steps.filter { it.taskId == taskId }.sortedBy { it.stepIndex }

    override suspend fun findStepById(stepId: Long): TaskStepEntity? =
        steps.firstOrNull { it.id == stepId }

    override suspend fun stepsForTaskWithStatus(taskId: Long, status: StepStatus): List<TaskStepEntity> =
        steps.filter { it.taskId == taskId && it.status == status }.sortedBy { it.stepIndex }
}

private class FakeConfirmationDaoForGateway : ConfirmationDao {
    val records = mutableListOf<ConfirmationRecordEntity>()
    private var nextId = 1L

    override suspend fun insert(confirmation: ConfirmationRecordEntity): Long {
        val id = if (confirmation.id == 0L) nextId++ else confirmation.id
        val saved = confirmation.copy(id = id)
        records.add(saved)
        return id
    }

    override suspend fun getForTask(taskId: Long): ConfirmationRecordEntity? =
        records.lastOrNull { it.taskId == taskId }

    override fun observeRecent(limit: Int): Flow<List<ConfirmationRecordEntity>> = emptyFlow()
    override suspend fun deleteOlderThan(beforeMs: Long) {}
    override suspend fun recordResponse(id: Long, confirmed: Boolean, respondedAt: Long) {
        val idx = records.indexOfFirst { it.id == id }
        if (idx >= 0) {
            records[idx] = records[idx].copy(userConfirmed = confirmed, respondedAt = respondedAt)
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class CommandGatewayTest {

    private lateinit var commandDao: FakeCommandDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var policyDecisionDao: FakePolicyDecisionDao
    private lateinit var toolRunner: FakeToolRunner
    private lateinit var taskDao: FakeTaskDao
    private lateinit var confirmationDao: FakeConfirmationDaoForGateway
    private lateinit var gateway: CommandGateway

    @Before
    fun setUp() {
        commandDao = FakeCommandDao()
        auditDao = FakeAuditDao()
        policyDecisionDao = FakePolicyDecisionDao()
        toolRunner = FakeToolRunner()
        taskDao = FakeTaskDao()
        confirmationDao = FakeConfirmationDaoForGateway()
        gateway = CommandGateway(
            ruleEngine = RuleEngine(),
            policyEngine = PolicyEngine(auditDao, policyDecisionDao, ioDispatcher = Dispatchers.Unconfined),
            toolRunner = toolRunner,
            commandDao = commandDao,
            auditDao = auditDao,
            redactor = Redactor(),
            dispatcher = Dispatchers.Unconfined,
            taskDao = taskDao,
            confirmationDao = confirmationDao
        )
    }

    // ─── Alarm: end to end ────────────────────────────────────────────────────

    @Test
    fun `alarm command runs end to end`() = runBlocking {
        toolRunner.nextResult =
            ToolRunResult("set_alarm", true, "Done. Alarm set for 7:00 AM.")

        val outcome = gateway.submitText("set alarm at 7am")

        assertTrue(outcome is CommandOutcome.Executed)
        assertEquals("Done. Alarm set for 7:00 AM.", (outcome as CommandOutcome.Executed).message)

        assertEquals(listOf("set_alarm"), toolRunner.invoked.map { it.first })
        assertEquals("7am", toolRunner.invoked.single().second["time"])

        assertEquals(1, commandDao.inserted.size)
        assertEquals(CommandGateway.STATUS_RECEIVED, commandDao.inserted.single().status)
        val update = commandDao.statusUpdates.single()
        assertEquals("system.set_alarm", update.intentName)
        assertEquals(CommandGateway.STATUS_COMPLETED, update.status)
        assertTrue(update.latencyMs >= 0)

        val decision = policyDecisionDao.decisions.single()
        assertEquals("system.set_alarm", decision.intentName)
        assertTrue(decision.allowed)
        assertFalse(decision.requiresConfirmation)

        assertEquals(listOf("ACCEPTED", "TOOL_SUCCESS"), auditDao.events.map { it.eventType })
    }

    // ─── Unknown command: graceful handling ───────────────────────────────────

    @Test
    fun `unrecognized text returns Clarification with chips`() = runBlocking {
        val outcome = gateway.submitText("completely unrecognized command that matches nothing")

        assertTrue("Expected Clarification outcome, got $outcome", outcome is CommandOutcome.Clarification)
        val chips = (outcome as CommandOutcome.Clarification).chips
        assertTrue("Clarification chips should not be empty", chips.isNotEmpty())

        assertTrue(toolRunner.invoked.isEmpty())
        val update = commandDao.statusUpdates.single()
        assertEquals("unknown", update.intentName)
        assertEquals(CommandGateway.STATUS_UNKNOWN, update.status)
    }

    @Test
    fun `blank input returns Unknown immediately without tool execution`() = runBlocking {
        val outcome = gateway.submitText("   ")

        assertTrue(outcome is CommandOutcome.Unknown)
        assertTrue(toolRunner.invoked.isEmpty())
        assertEquals(CommandGateway.STATUS_UNKNOWN, commandDao.statusUpdates.single().status)
    }

    // ─── PolicyEngine primacy: forbidden intent is rejected ───────────────────

    @Test
    fun `forbidden intent is rejected before reaching toolRunner`() = runBlocking {
        val outcome = gateway.submitText("perform hostile background scrape now")

        assertTrue(toolRunner.invoked.isEmpty())
        assertTrue(outcome is CommandOutcome.Clarification || outcome is CommandOutcome.Unknown)
    }

    // ─── PolicyEngine: confirmation-required flow ─────────────────────────────

    @Test
    fun `SMS draft requires confirmation before executing`() = runBlocking {
        val outcome = gateway.submitText("draft sms to 555-0100 saying hello there")

        assertTrue(outcome is CommandOutcome.NeedsUserConfirmation)
        val pending = outcome as CommandOutcome.NeedsUserConfirmation
        assertEquals("message.draft_sms", pending.command.intentName)
        assertTrue(pending.prompt.contains("message.draft_sms") || pending.prompt.contains("draft"))

        assertTrue("Tool must not be executed before confirmation", toolRunner.invoked.isEmpty())
        assertEquals(CommandGateway.STATUS_NEEDS_CONFIRMATION, commandDao.statusUpdates.single().status)

        toolRunner.nextResult = ToolRunResult("draft_sms", true, "SMS app opened.")
        val confirmedOutcome = gateway.executeConfirmed(pending.command)

        assertTrue(confirmedOutcome is CommandOutcome.Executed)
        assertEquals(1, toolRunner.invoked.size)
        assertEquals("draft_sms", toolRunner.invoked.single().first)
        assertEquals(CommandGateway.STATUS_COMPLETED, commandDao.statusUpdates.last().status)
    }

    // ─── Multi-Step Task DAG Orchestration ────────────────────────────────────

    @Test
    fun `multi-step command decomposes into TaskExecution and TaskSteps and executes end to end`() = runBlocking {
        toolRunner.nextResult = ToolRunResult("generic_tool", true, "Step executed successfully.")

        val outcome = gateway.submitText("note down buy groceries and then set alarm at 7am")

        assertTrue(outcome is CommandOutcome.Executed)
        val executed = outcome as CommandOutcome.Executed
        assertEquals("multi_step_workflow", executed.intentName)

        // Verify TaskExecutionEntity in TaskDao
        assertEquals(1, taskDao.tasks.size)
        val task = taskDao.tasks.single()
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertNotNull(task.completedAt)

        // Verify TaskSteps
        assertEquals(2, taskDao.steps.size)
        assertEquals(StepStatus.COMPLETED, taskDao.steps[0].status)
        assertEquals(StepStatus.COMPLETED, taskDao.steps[1].status)
        assertEquals(listOf("create_note", "set_alarm"), toolRunner.invoked.map { it.first })
    }

    @Test
    fun `multi-step command pauses mid-chain on HIGH-risk intent awaiting user confirmation`() = runBlocking {
        toolRunner.nextResult = ToolRunResult("note_created", true, "Note saved: groceries")

        val outcome = gateway.submitText("create note buy eggs and then draft sms to Alice saying running late")

        // Step 1 completed, Step 2 paused on high-risk confirmation
        assertTrue(outcome is CommandOutcome.NeedsUserConfirmation)
        val pending = outcome as CommandOutcome.NeedsUserConfirmation
        assertEquals("message.draft_sms", pending.command.intentName)
        assertEquals(1, pending.stepIndex)

        // Task state
        val task = taskDao.tasks.single()
        assertEquals(TaskStatus.PAUSED, task.status)
        assertEquals(StepStatus.COMPLETED, taskDao.steps[0].status)
        assertEquals(StepStatus.AWAITING_CONFIRMATION, taskDao.steps[1].status)
        assertEquals(1, confirmationDao.records.size)

        // User approves confirmed step (and optionally edits message)
        toolRunner.nextResult = ToolRunResult("draft_sms", true, "SMS opened for Alice.")
        val resumedOutcome = gateway.executeConfirmed(pending.command)

        assertTrue(resumedOutcome is CommandOutcome.Executed)
        assertEquals(TaskStatus.COMPLETED, taskDao.tasks.single().status)
        assertEquals(StepStatus.COMPLETED, taskDao.steps[1].status)
        assertEquals(listOf("create_note", "draft_sms"), toolRunner.invoked.map { it.first })
    }

    @Test
    fun `multi-step cancellation halts remaining steps and logs USER_CANCELLED`() = runBlocking {
        gateway.submitText("create note buy milk and then draft sms to Bob saying meet me")

        val task = taskDao.tasks.single()
        val taskId = task.id
        assertEquals(TaskStatus.PAUSED, task.status)

        // User cancels
        val cancelled = gateway.cancelTask(taskId, "User dismissed")
        assertTrue(cancelled)

        assertEquals(TaskStatus.CANCELLED, taskDao.tasks.single().status)
        assertEquals(StepStatus.CANCELLED, taskDao.steps[1].status)

        val cancelAudit = auditDao.events.firstOrNull { it.eventType == "USER_CANCELLED" }
        assertNotNull(cancelAudit)
    }

    @Test
    fun `MAX_STEPS_PER_TASK limits chained tasks to 5 steps`() = runBlocking {
        val compound = "note down step 1 and then note down step 2 and then note down step 3 and then note down step 4 and then note down step 5 and then note down step 6 and then note down step 7"
        gateway.submitText(compound)

        assertTrue("Steps must not exceed MAX_STEPS_PER_TASK", taskDao.steps.size <= CommandGateway.MAX_STEPS_PER_TASK)
        assertEquals(CommandGateway.MAX_STEPS_PER_TASK, taskDao.steps.size)
    }

    @Test
    fun `process-death recovery surfaces paused tasks from TaskDao`() = runBlocking {
        gateway.submitText("create note task 1 and then draft sms to Charlie saying hi")

        val paused = gateway.getPausedTasks()
        assertEquals(1, paused.size)
        assertEquals("multi_step_workflow", paused.single().intentName)
        assertEquals(TaskStatus.PAUSED, paused.single().status)
    }

    @Test
    fun `verb-lookahead and quote-safe multi-step splitter prevents false positive clause splitting`() = runBlocking {
        toolRunner.nextResult = ToolRunResult("note_created", true, "Note created successfully.")

        // 1. Single-step command with benign internal "then" that should NOT split
        val singleOutcome = gateway.submitText("create a note saying if build fails then check logs")
        assertTrue(singleOutcome is CommandOutcome.Executed)
        val singleExecuted = singleOutcome as CommandOutcome.Executed
        assertEquals("note.create", singleExecuted.intentName)
        assertEquals(0, taskDao.tasks.size) // No multi-step DAG created

        // 2. Multi-step command with verb lookahead "set" that SHOULD split into a 2-step DAG
        val multiOutcome = gateway.submitText("create a note about deployment and then set a timer for 10 minutes")
        assertTrue(multiOutcome is CommandOutcome.Executed)
        val multiExecuted = multiOutcome as CommandOutcome.Executed
        assertEquals("multi_step_workflow", multiExecuted.intentName)
        assertEquals(1, taskDao.tasks.size)
        assertEquals(2, taskDao.steps.size)
    }
}
