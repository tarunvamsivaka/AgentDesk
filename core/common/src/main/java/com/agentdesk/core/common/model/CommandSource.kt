package com.agentdesk.core.common.model

/**
 * Origin of a command sent to the agent.
 */
enum class CommandSource {
    USER_CHAT,
    QUICK_CHIP,
    SHARE_BRIDGE,
    WIDGET,
    NOTIFICATION_ACTION,
    INTERNAL_SYSTEM
}
