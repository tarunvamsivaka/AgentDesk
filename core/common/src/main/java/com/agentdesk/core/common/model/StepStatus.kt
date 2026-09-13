package com.agentdesk.core.common.model

/**
 * Lifecycle state of an individual step within a task execution.
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}
