package com.agentdesk.core.common.model

/**
 * Tracks whether the user has granted, denied, or not yet responded to a consent request.
 */
enum class ConsentState {
    UNKNOWN,
    GRANTED,
    DENIED,
    REVOKED
}
