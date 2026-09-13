package com.agentdesk.core.settings

/**
 * Feature flag keys. All cloud and heavy AI features are DISABLED by default.
 * Defaults are declared here and used by ConsentRepository and ResourceGovernor.
 */
object FeatureFlagKeys {
    const val CLOUD_SYNC_ENABLED = "flag.cloud_sync_enabled"
    const val ON_DEVICE_LLM_ENABLED = "flag.on_device_llm_enabled"
    const val BACKGROUND_INDEXING_ENABLED = "flag.background_indexing_enabled"
    const val EMBEDDING_ENABLED = "flag.embedding_enabled"
    const val SHARE_BRIDGE_ENABLED = "flag.share_bridge_enabled"
    const val AUDIT_LOG_ENABLED = "flag.audit_log_enabled"
    const val APP_LOCK_ENABLED = "flag.app_lock_enabled"
    const val KNOWLEDGE_SEARCH_ENABLED = "flag.knowledge_search_enabled"
    const val DEVICE_HEALTH_ENABLED = "flag.device_health_enabled"

    /** Safe defaults — cloud and heavy features off. */
    val defaults: Map<String, Boolean> = mapOf(
        CLOUD_SYNC_ENABLED to false,
        ON_DEVICE_LLM_ENABLED to false,
        BACKGROUND_INDEXING_ENABLED to true,
        EMBEDDING_ENABLED to false,
        SHARE_BRIDGE_ENABLED to true,
        AUDIT_LOG_ENABLED to true,
        APP_LOCK_ENABLED to false,
        KNOWLEDGE_SEARCH_ENABLED to true,
        DEVICE_HEALTH_ENABLED to true,
    )
}
