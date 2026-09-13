package com.agentdesk.core.common.model

/**
 * Lifecycle state of an agent task execution.
 */
enum class TaskStatus {
    PENDING,
    RUNNING,
    AWAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
}
