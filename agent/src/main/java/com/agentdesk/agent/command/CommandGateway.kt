package com.agentdesk.agent.command

import com.agentdesk.agent.policy.PolicyEngine
import com.agentdesk.agent.rule.Intents
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.CommandSource
import com.agentdesk.core.common.model.StepStatus
import com.agentdesk.core.common.model.TaskStatus
import com.agentdesk.core.common.tools.ToolRunResult
import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.core.device.ResourceGovernor
import com.agentdesk.core.persistence.audit.AuditReceiptSigner
import com.agentdesk.core.persistence.audit.DefaultAuditReceiptSigner
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.CommandDao
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.TaskDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.CommandRecordEntity
import com.agentdesk.core.persistence.entity.ConfirmationRecordEntity
import com.agentdesk.core.persistence.entity.TaskExecutionEntity
import com.agentdesk.core.persistence.entity.TaskStepEntity
import com.agentdesk.core.security.Redactor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for all incoming commands.
 *
 * Supports:
 * 1. 4-Tier Intent Recognition Hierarchy (Regex -> Habit -> SLM -> Fallback)
 * 2. Multi-step Task DAG Decomposition & Checkpoint Recovery via [TaskDao]
 * 3. Human-in-the-Loop (HITL) Pause & Resume on high-risk intents
 * 4. Cryptographic Audit Receipt Chaining via [AuditReceiptSigner]
 * 5. Hardware-aware governance via [ResourceGovernor]
 */
@Singleton
class CommandGateway @Inject constructor(
    private val ruleEngine: RuleEngine,
    private val policyEngine: PolicyEngine,
    private val toolRunner: ToolRunner,
    private val commandDao: CommandDao,
    private val auditDao: AuditDao,
    private val redactor: Redactor,
    private val dispatcher: CoroutineDispatcher,
    private val llmManager: com.agentdesk.agent.intent.OnDeviceLlmManager = com.agentdesk.agent.intent.DefaultOnDeviceLlmManager(),
    private val resourceGovernor: ResourceGovernor? = null,
    private val habitPredictionEngine: com.agentdesk.agent.habit.HabitPredictionEngine? = null,
    private val taskDao: TaskDao? = null,
    private val confirmationDao: ConfirmationDao? = null,
    private val auditReceiptSigner: AuditReceiptSigner = DefaultAuditReceiptSigner()
) {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.5f
        const val MAX_STEPS_PER_TASK = 5
        const val STEP_TIMEOUT_MS = 15_000L

        /** CommandRecord status vocabulary. */
        const val STATUS_RECEIVED = "RECEIVED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
        const val STATUS_UNKNOWN = "UNKNOWN"
    }

    suspend fun submitText(
        rawInput: String,
        source: CommandSource = CommandSource.USER_CHAT,
        threadId: Long? = null,
        thermalThrottled: Boolean = false
    ): CommandOutcome = withContext(dispatcher) {
        val startedAt = System.currentTimeMillis()
        val normalized = rawInput.trim().lowercase()

        // Step 1: Persist command record as RECEIVED (rawInput is redacted — no PII at rest)
        val commandId = commandDao.insert(
            CommandRecordEntity(
                rawInput = redactor.redact(rawInput),
                normalizedInput = redactor.redact(normalized),
                source = source,
                threadId = threadId
            )
        )

        if (normalized.isBlank()) {
            finish(commandId, "unknown", STATUS_UNKNOWN, startedAt)
            return@withContext CommandOutcome.Unknown(rawInput)
        }

        // Step 2: Check for compound multi-step command
        val clauses = decomposeIntoClauses(rawInput)
        if (clauses.size > 1) {
            val multiStepResult = executeMultiStepTask(
                rawInput = rawInput,
                clauses = clauses,
                commandId = commandId,
                source = source,
                threadId = threadId,
                startedAt = startedAt,
                thermalThrottled = thermalThrottled
            )
            if (multiStepResult != null) {
                return@withContext multiStepResult
            }
        }

        // Single-command flow: 4-Tier Intent Recognition Hierarchy
        val resolution = resolveIntent(normalized, thermalThrottled)
        if (resolution.isClarification) {
            finish(commandId, resolution.intentName, STATUS_UNKNOWN, startedAt)
            return@withContext CommandOutcome.Clarification(rawInput, resolution.clarificationChips)
        }
        if (resolution.intentName.isBlank() || resolution.intentName == "unknown") {
            finish(commandId, "unknown", STATUS_UNKNOWN, startedAt)
            return@withContext CommandOutcome.Clarification(
                rawInput,
                resolution.clarificationChips.ifEmpty { listOf("Search Library", "Set Alarm", "Create Note") }
            )
        }

        val command = Command(
            rawInput = rawInput,
            normalizedInput = normalized,
            intentName = resolution.intentName,
            entities = resolution.entities,
            confidence = resolution.confidence,
            source = source
        )

        // Step 3: Policy check (PolicyEngine always wins)
        when (val policy = policyEngine.evaluate(command, commandId)) {
            is CommandResult.Rejected -> {
                finish(commandId, resolution.intentName, STATUS_REJECTED, startedAt)
                CommandOutcome.Rejected(policy.reason)
            }
            is CommandResult.NeedsConfirmation -> {
                finish(commandId, resolution.intentName, STATUS_NEEDS_CONFIRMATION, startedAt)
                CommandOutcome.NeedsUserConfirmation(policy.command, policy.prompt)
            }
            is CommandResult.Accepted ->
                executeCommand(policy.command, commandId, startedAt)
            is CommandResult.Unknown ->
                CommandOutcome.Unknown(rawInput)
        }
    }

    /**
     * Executes a policy-approved command after the user confirms it.
     * If the command is part of a multi-step task, resumes remaining DAG steps.
     */
    suspend fun executeConfirmed(
        command: Command,
        threadId: Long? = null
    ): CommandOutcome = withContext(dispatcher) {
        val startedAt = System.currentTimeMillis()

        // Case 1: Multi-step task continuation
        if (command.taskId != null && taskDao != null) {
            return@withContext resumeMultiStepTaskAfterConfirmation(command, startedAt, threadId)
        }

        // Case 2: Standard single-command confirmation
        val commandId = commandDao.insert(
            CommandRecordEntity(
                rawInput = redactor.redact(command.rawInput),
                normalizedInput = redactor.redact(command.normalizedInput),
                intentName = command.intentName,
                confidence = command.confidence,
                source = command.source,
                threadId = threadId
            )
        )
        executeCommand(command, commandId, startedAt)
    }

    /**
     * Cancels an incomplete or awaiting task DAG and records an audit event.
     */
    suspend fun cancelTask(taskId: Long, reason: String = "User cancelled"): Boolean = withContext(dispatcher) {
        if (taskDao == null) return@withContext false
        val task = taskDao.findById(taskId) ?: return@withContext false

        val steps = taskDao.stepsForTask(taskId)
        for (step in steps) {
            if (step.status == StepStatus.PENDING || step.status == StepStatus.AWAITING_CONFIRMATION || step.status == StepStatus.RUNNING) {
                taskDao.updateStep(step.copy(status = StepStatus.CANCELLED, completedAt = System.currentTimeMillis()))
            }
        }
        taskDao.updateTask(task.copy(status = TaskStatus.CANCELLED, errorMessage = reason, completedAt = System.currentTimeMillis()))

        insertAuditEvent(
            AuditEventEntity(
                eventType = "USER_CANCELLED",
                actor = "USER",
                targetType = "TASK",
                targetId = taskId.toString(),
                summary = "Task $taskId cancelled: $reason",
                riskLevel = com.agentdesk.core.common.model.RiskLevel.NONE
            )
        )
        true
    }

    /**
     * Process-death recovery: returns all paused or incomplete tasks.
     */
    suspend fun getPausedTasks(): List<TaskExecutionEntity> = withContext(dispatcher) {
        taskDao?.getIncompleteTasks() ?: emptyList()
    }

    // ---------------------------------------------------------------------------
    // Multi-Step Task DAG Implementation
    // ---------------------------------------------------------------------------

    private fun decomposeIntoClauses(rawInput: String): List<String> {
        val delimiters = listOf(
            Regex("(?i)\\s+(?:and\\s+then|then|&\\s+then)\\s+"),
            Regex(";\\s*")
        )
        var clauses = listOf(rawInput)
        for (delimiter in delimiters) {
            clauses = clauses.flatMap { it.split(delimiter).map { s -> s.trim() }.filter { s -> s.isNotBlank() } }
        }
        return clauses.take(MAX_STEPS_PER_TASK)
    }

    private data class ParsedClause(
        val clause: String,
        val intentName: String,
        val entities: Map<String, String>,
        val confidence: Float
    )

    private suspend fun executeMultiStepTask(
        rawInput: String,
        clauses: List<String>,
        commandId: Long,
        source: CommandSource,
        threadId: Long?,
        startedAt: Long,
        thermalThrottled: Boolean
    ): CommandOutcome? {
        val parsedSteps = mutableListOf<ParsedClause>()
        for (clause in clauses) {
            val res = resolveIntent(clause.trim().lowercase(), thermalThrottled)
            if (res.intentName.isBlank() || res.intentName == "unknown" || res.isClarification) {
                return null // Not a valid multi-step sequence of recognizable intents
            }
            parsedSteps.add(ParsedClause(clause, res.intentName, res.entities, res.confidence))
        }

        if (parsedSteps.size < 2) return null

        // Persist parent task execution
        val taskId = taskDao?.insertTask(
            TaskExecutionEntity(
                commandId = commandId,
                intentName = "multi_step_workflow",
                status = TaskStatus.RUNNING,
                startedAt = startedAt
            )
        ) ?: 0L

        // Persist child steps
        val stepEntitiesList = mutableListOf<TaskStepEntity>()
        for ((idx, parsed) in parsedSteps.withIndex()) {
            val stepEntity = TaskStepEntity(
                taskId = taskId,
                stepIndex = idx,
                toolName = intentToToolId(parsed.intentName),
                status = StepStatus.PENDING,
                inputSummary = redactor.redact(parsed.clause),
                startedAt = System.currentTimeMillis()
            )
            val stepId = taskDao?.insertStep(stepEntity) ?: 0L
            stepEntitiesList.add(stepEntity.copy(id = stepId))
        }

        var previousOutput = ""
        val stepMessages = mutableListOf<String>()
        val stepResults = mutableListOf<Pair<String, String>>()

        for ((idx, parsed) in parsedSteps.withIndex()) {
            val stepRecord = stepEntitiesList.getOrNull(idx)

            // Inter-step artifact and context passing
            val entities = parsed.entities.toMutableMap()
            if (previousOutput.isNotBlank()) {
                entities["previous_output"] = previousOutput
                if (parsed.intentName == Intents.SMS_DRAFT && !entities.containsKey("message")) {
                    entities["message"] = previousOutput
                } else if (parsed.intentName == Intents.EMAIL_DRAFT && !entities.containsKey("body")) {
                    entities["body"] = previousOutput
                } else if (parsed.intentName == Intents.CALENDAR_CREATE && !entities.containsKey("description")) {
                    entities["description"] = previousOutput
                }
            }

            val stepCommand = Command(
                rawInput = parsed.clause,
                normalizedInput = parsed.clause.lowercase(),
                intentName = parsed.intentName,
                entities = entities,
                confidence = parsed.confidence,
                source = source,
                taskId = taskId,
                stepIndex = idx
            )

            when (val policy = policyEngine.evaluate(stepCommand, commandId)) {
                is CommandResult.Rejected -> {
                    stepRecord?.let {
                        taskDao?.updateStep(it.copy(status = StepStatus.FAILED, outputSummary = policy.reason, completedAt = System.currentTimeMillis()))
                    }
                    markRemainingStepsSkipped(taskId, idx + 1)
                    updateTaskStatus(taskId, TaskStatus.FAILED, policy.reason)
                    finish(commandId, parsed.intentName, STATUS_REJECTED, startedAt)
                    return CommandOutcome.Rejected(policy.reason)
                }

                is CommandResult.NeedsConfirmation -> {
                    stepRecord?.let {
                        taskDao?.updateStep(it.copy(status = StepStatus.AWAITING_CONFIRMATION))
                    }
                    updateTaskStatus(taskId, TaskStatus.PAUSED)
                    if (confirmationDao != null && taskId > 0L) {
                        confirmationDao.insert(
                            ConfirmationRecordEntity(
                                taskId = taskId,
                                promptShown = policy.prompt,
                                userConfirmed = null,
                                promptedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    finish(commandId, parsed.intentName, STATUS_NEEDS_CONFIRMATION, startedAt)
                    return CommandOutcome.NeedsUserConfirmation(
                        command = stepCommand,
                        prompt = policy.prompt,
                        taskId = taskId,
                        stepIndex = idx
                    )
                }

                is CommandResult.Accepted -> {
                    stepRecord?.let {
                        taskDao?.updateStep(it.copy(status = StepStatus.RUNNING))
                    }
                    val toolId = intentToToolId(parsed.intentName)
                    val result = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                        toolRunner.execute(toolId, entities, commandId)
                    } ?: ToolRunResult(toolId, false, "Execution timed out after ${STEP_TIMEOUT_MS / 1000}s")

                    insertAuditEvent(
                        AuditEventEntity(
                            eventType = if (result.success) "TOOL_SUCCESS" else "TOOL_ERROR",
                            actor = source.name,
                            targetType = "TOOL",
                            targetId = toolId,
                            summary = result.message,
                            riskLevel = stepCommand.riskLevel
                        )
                    )

                    if (!result.success) {
                        stepRecord?.let {
                            taskDao?.updateStep(it.copy(status = StepStatus.FAILED, outputSummary = result.message, completedAt = System.currentTimeMillis()))
                        }
                        markRemainingStepsSkipped(taskId, idx + 1)
                        updateTaskStatus(taskId, TaskStatus.FAILED, result.message)
                        finish(commandId, parsed.intentName, STATUS_FAILED, startedAt)
                        return CommandOutcome.Executed("Step ${idx + 1} failed: ${result.message}", parsed.intentName)
                    }

                    stepRecord?.let {
                        taskDao?.updateStep(it.copy(status = StepStatus.COMPLETED, outputSummary = result.message, completedAt = System.currentTimeMillis()))
                    }
                    previousOutput = result.message
                    stepMessages.add(result.message)
                    stepResults.add(parsed.intentName to result.message)
                }

                is CommandResult.Unknown -> {
                    return CommandOutcome.Unknown(parsed.clause)
                }
            }
        }

        updateTaskStatus(taskId, TaskStatus.COMPLETED)
        finish(commandId, "multi_step_workflow", STATUS_COMPLETED, startedAt)
        return CommandOutcome.Executed(
            message = stepMessages.joinToString("\n"),
            intentName = "multi_step_workflow",
            stepResults = stepResults
        )
    }

    private suspend fun resumeMultiStepTaskAfterConfirmation(
        approvedCommand: Command,
        startedAt: Long,
        threadId: Long?
    ): CommandOutcome {
        val taskId = approvedCommand.taskId ?: return executeCommand(approvedCommand, 0L, startedAt)
        val allSteps = taskDao?.stepsForTask(taskId) ?: emptyList()
        val currentStep = allSteps.getOrNull(approvedCommand.stepIndex)

        // 1. Execute the confirmed step
        val toolId = intentToToolId(approvedCommand.intentName)
        val result = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            toolRunner.execute(toolId, approvedCommand.entities, taskId)
        } ?: ToolRunResult(toolId, false, "Execution timed out")

        insertAuditEvent(
            AuditEventEntity(
                eventType = if (result.success) "TOOL_SUCCESS" else "TOOL_ERROR",
                actor = approvedCommand.source.name,
                targetType = "TOOL",
                targetId = toolId,
                summary = result.message,
                riskLevel = approvedCommand.riskLevel
            )
        )

        currentStep?.let {
            taskDao?.updateStep(
                it.copy(
                    status = if (result.success) StepStatus.COMPLETED else StepStatus.FAILED,
                    outputSummary = result.message,
                    completedAt = System.currentTimeMillis()
                )
            )
        }

        if (!result.success) {
            markRemainingStepsSkipped(taskId, approvedCommand.stepIndex + 1)
            updateTaskStatus(taskId, TaskStatus.FAILED, result.message)
            return CommandOutcome.Executed("Step ${approvedCommand.stepIndex + 1} failed: ${result.message}", approvedCommand.intentName)
        }

        var previousOutput = result.message
        val stepMessages = mutableListOf(result.message)
        val stepResults = mutableListOf(approvedCommand.intentName to result.message)

        // 2. Continue with subsequent steps
        for (idx in (approvedCommand.stepIndex + 1) until allSteps.size) {
            val step = allSteps[idx]
            val clause = step.inputSummary
            val res = resolveIntent(clause.lowercase(), thermalThrottled = false)
            val entities = res.entities.toMutableMap()
            if (previousOutput.isNotBlank()) {
                entities["previous_output"] = previousOutput
                if (res.intentName == Intents.SMS_DRAFT && !entities.containsKey("message")) {
                    entities["message"] = previousOutput
                } else if (res.intentName == Intents.EMAIL_DRAFT && !entities.containsKey("body")) {
                    entities["body"] = previousOutput
                }
            }

            val stepCommand = Command(
                rawInput = clause,
                normalizedInput = clause.lowercase(),
                intentName = res.intentName,
                entities = entities,
                confidence = res.confidence,
                source = approvedCommand.source,
                taskId = taskId,
                stepIndex = idx
            )

            when (val policy = policyEngine.evaluate(stepCommand, taskId)) {
                is CommandResult.Rejected -> {
                    taskDao?.updateStep(step.copy(status = StepStatus.FAILED, outputSummary = policy.reason, completedAt = System.currentTimeMillis()))
                    markRemainingStepsSkipped(taskId, idx + 1)
                    updateTaskStatus(taskId, TaskStatus.FAILED, policy.reason)
                    return CommandOutcome.Rejected(policy.reason)
                }

                is CommandResult.NeedsConfirmation -> {
                    taskDao?.updateStep(step.copy(status = StepStatus.AWAITING_CONFIRMATION))
                    updateTaskStatus(taskId, TaskStatus.PAUSED)
                    if (confirmationDao != null) {
                        confirmationDao.insert(
                            ConfirmationRecordEntity(
                                taskId = taskId,
                                promptShown = policy.prompt,
                                userConfirmed = null,
                                promptedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    return CommandOutcome.NeedsUserConfirmation(
                        command = stepCommand,
                        prompt = policy.prompt,
                        taskId = taskId,
                        stepIndex = idx
                    )
                }

                is CommandResult.Accepted -> {
                    taskDao?.updateStep(step.copy(status = StepStatus.RUNNING))
                    val stepToolId = intentToToolId(res.intentName)
                    val stepResult = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                        toolRunner.execute(stepToolId, entities, taskId)
                    } ?: ToolRunResult(stepToolId, false, "Execution timed out")

                    insertAuditEvent(
                        AuditEventEntity(
                            eventType = if (stepResult.success) "TOOL_SUCCESS" else "TOOL_ERROR",
                            actor = approvedCommand.source.name,
                            targetType = "TOOL",
                            targetId = stepToolId,
                            summary = stepResult.message,
                            riskLevel = stepCommand.riskLevel
                        )
                    )

                    if (!stepResult.success) {
                        taskDao?.updateStep(step.copy(status = StepStatus.FAILED, outputSummary = stepResult.message, completedAt = System.currentTimeMillis()))
                        markRemainingStepsSkipped(taskId, idx + 1)
                        updateTaskStatus(taskId, TaskStatus.FAILED, stepResult.message)
                        return CommandOutcome.Executed("Step ${idx + 1} failed: ${stepResult.message}", res.intentName)
                    }

                    taskDao?.updateStep(step.copy(status = StepStatus.COMPLETED, outputSummary = stepResult.message, completedAt = System.currentTimeMillis()))
                    previousOutput = stepResult.message
                    stepMessages.add(stepResult.message)
                    stepResults.add(res.intentName to stepResult.message)
                }

                is CommandResult.Unknown -> return CommandOutcome.Unknown(clause)
            }
        }

        updateTaskStatus(taskId, TaskStatus.COMPLETED)
        return CommandOutcome.Executed(
            message = stepMessages.joinToString("\n"),
            intentName = "multi_step_workflow",
            stepResults = stepResults
        )
    }

    private suspend fun markRemainingStepsSkipped(taskId: Long, startIdx: Int) {
        if (taskDao == null || taskId <= 0L) return
        val steps = taskDao.stepsForTask(taskId)
        for (i in startIdx until steps.size) {
            val step = steps[i]
            if (step.status == StepStatus.PENDING) {
                taskDao.updateStep(step.copy(status = StepStatus.SKIPPED, completedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun updateTaskStatus(taskId: Long, status: TaskStatus, errorMessage: String? = null) {
        if (taskDao == null || taskId <= 0L) return
        val task = taskDao.findById(taskId) ?: return
        taskDao.updateTask(
            task.copy(
                status = status,
                errorMessage = errorMessage,
                completedAt = if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) System.currentTimeMillis() else null
            )
        )
    }

    // ---------------------------------------------------------------------------
    // Internal Resolution & Single Command Helpers
    // ---------------------------------------------------------------------------

    private data class IntentResolution(
        val intentName: String,
        val entities: Map<String, String>,
        val confidence: Float,
        val isClarification: Boolean = false,
        val clarificationChips: List<String> = emptyList()
    )

    private suspend fun resolveIntent(normalized: String, thermalThrottled: Boolean): IntentResolution {
        // Tier 0: Regex RuleEngine (<5ms)
        val ruleResult = ruleEngine.evaluate(normalized)
        if (ruleResult != null && ruleResult.confidence >= CONFIDENCE_THRESHOLD) {
            return IntentResolution(ruleResult.intentName, ruleResult.entities, ruleResult.confidence)
        }

        // Habit prediction boost
        val boosted = habitPredictionEngine?.boostIntentIfHabitual(normalized)
        if (boosted != null && boosted.confidence >= CONFIDENCE_THRESHOLD) {
            return IntentResolution(boosted.intentName, boosted.entities, boosted.confidence)
        }

        // Tier 1 & 2: On-device SLM
        val throttled = thermalThrottled || (resourceGovernor != null && !resourceGovernor.canRunLlm())
        val slmResult = llmManager.classifyIntent(normalized, thermalThrottled = throttled)

        if (slmResult.intentName == "clarification") {
            return IntentResolution(
                intentName = "clarification",
                entities = emptyMap(),
                confidence = slmResult.confidence,
                isClarification = true,
                clarificationChips = slmResult.clarificationChips
            )
        }

        if (slmResult.confidence >= CONFIDENCE_THRESHOLD && slmResult.intentName != "unknown") {
            return IntentResolution(slmResult.intentName, slmResult.entities, slmResult.confidence)
        }

        return IntentResolution(
            intentName = "unknown",
            entities = emptyMap(),
            confidence = slmResult.confidence,
            isClarification = true,
            clarificationChips = slmResult.clarificationChips.ifEmpty { listOf("Search Library", "Set Alarm", "Create Note") }
        )
    }

    private suspend fun executeCommand(
        command: Command,
        commandId: Long,
        startedAt: Long
    ): CommandOutcome.Executed {
        val toolId = intentToToolId(command.intentName)
        val result = toolRunner.execute(toolId, command.entities, commandId)

        insertAuditEvent(
            AuditEventEntity(
                eventType = if (result.success) "TOOL_SUCCESS" else "TOOL_ERROR",
                actor = command.source.name,
                targetType = "TOOL",
                targetId = toolId,
                summary = result.message,
                riskLevel = command.riskLevel
            )
        )

        finish(
            commandId,
            command.intentName,
            if (result.success) STATUS_COMPLETED else STATUS_FAILED,
            startedAt
        )
        return CommandOutcome.Executed(result.message, command.intentName)
    }

    private suspend fun insertAuditEvent(event: AuditEventEntity) {
        try {
            val count = auditDao.getEventCount()
            val prevDigest = auditDao.getLatestEvent()?.receiptDigest ?: auditReceiptSigner.genesisDigest
            val signedEvent = auditReceiptSigner.signEvent(
                event = event,
                sequence = count + 1,
                prevDigest = prevDigest
            )
            auditDao.insert(signedEvent)
        } catch (_: Throwable) {
            auditDao.insert(event)
        }
    }

    private suspend fun finish(commandId: Long, intentName: String, status: String, startedAt: Long) {
        if (commandId > 0L) {
            commandDao.updateResult(commandId, intentName, status, System.currentTimeMillis() - startedAt)
        }
    }

    private fun intentToToolId(intentName: String): String = when (intentName) {
        Intents.SET_ALARM       -> "set_alarm"
        Intents.SET_TIMER       -> "set_timer"
        Intents.NOTE_CREATE     -> "create_note"
        Intents.NOTE_APPEND     -> "append_note"
        Intents.NOTE_SEARCH     -> "search_notes"
        Intents.APP_OPEN        -> "open_app"
        Intents.SETTINGS_OPEN   -> "system_settings"
        Intents.CALENDAR_CREATE -> "create_calendar_event"
        Intents.SMS_DRAFT       -> "draft_sms"
        Intents.EMAIL_DRAFT     -> "draft_email"
        Intents.DIAL_NUMBER     -> "open_dialer"
        Intents.LIBRARY_SEARCH  -> "library_search"
        Intents.WEB_SEARCH      -> "web_search"
        Intents.NAVIGATE_MAP    -> "navigate_maps"
        Intents.DEVICE_HEALTH   -> "device_health"
        else                    -> intentName
    }
}
