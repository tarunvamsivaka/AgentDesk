package com.agentdesk.core.settings

/**
 * Canonical keys for consent records stored in the database.
 * Every key corresponds to a feature that requires explicit user consent.
 */
object ConsentKeys {
    const val LOCAL_KNOWLEDGE_INDEXING = "consent.local_knowledge_indexing"
    const val SHARE_BRIDGE = "consent.share_bridge"
    const val DEVICE_HEALTH_MONITORING = "consent.device_health_monitoring"
    const val AUDIT_LOG = "consent.audit_log"
    const val APP_LOCK = "consent.app_lock"
    const val CLOUD_SYNC = "consent.cloud_sync" // disabled by default
    const val NOTIFICATIONS = "consent.notifications"
    const val CALENDAR_ACCESS = "consent.calendar_access"
    const val CONTACTS_ACCESS = "consent.contacts_access"
}
