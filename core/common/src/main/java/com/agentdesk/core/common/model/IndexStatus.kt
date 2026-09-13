package com.agentdesk.core.common.model

/**
 * Status of a document indexing job.
 */
enum class IndexStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
