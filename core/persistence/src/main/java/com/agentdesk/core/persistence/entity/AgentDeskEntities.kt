package com.agentdesk.core.persistence.entity

import androidx.room.*
import com.agentdesk.core.common.model.*

// ────────────────────────────────────────────────────────────────────────────
// USER PROFILE
// ────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Long = 1L,
    @ColumnInfo(name = "display_name") val displayName: String = "",
    @ColumnInfo(name = "onboarding_complete") val onboardingComplete: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// CONSENT
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "consent_record",
    indices = [Index(value = ["consent_key"], unique = true)]
)
data class ConsentRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "consent_key") val consentKey: String,
    @ColumnInfo(name = "state") val state: ConsentState = ConsentState.UNKNOWN,
    @ColumnInfo(name = "granted_at") val grantedAt: Long? = null,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// PERMISSIONS
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "permission_state",
    indices = [Index(value = ["permission_key"], unique = true)]
)
data class PermissionStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "permission_key") val permissionKey: String,
    @ColumnInfo(name = "android_permission") val androidPermission: String,
    @ColumnInfo(name = "is_granted") val isGranted: Boolean = false,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// USER PREFERENCES
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "user_preference",
    indices = [Index(value = ["pref_key"], unique = true)]
)
data class UserPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pref_key") val prefKey: String,
    @ColumnInfo(name = "pref_value") val prefValue: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// FEATURE FLAGS
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "feature_flag_override",
    indices = [Index(value = ["flag_key"], unique = true)]
)
data class FeatureFlagOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "flag_key") val flagKey: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "override_source") val overrideSource: String = "USER",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// DEVICE CAPABILITY SNAPSHOT
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "device_capability_snapshot",
    indices = [Index(value = ["captured_at"])]
)
data class DeviceCapabilitySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "device_tier") val deviceTier: DeviceTier = DeviceTier.BALANCED,
    @ColumnInfo(name = "total_ram_mb") val totalRamMb: Long = 0,
    @ColumnInfo(name = "available_ram_mb") val availableRamMb: Long = 0,
    @ColumnInfo(name = "cpu_cores") val cpuCores: Int = 0,
    @ColumnInfo(name = "total_storage_mb") val totalStorageMb: Long = 0,
    @ColumnInfo(name = "available_storage_mb") val availableStorageMb: Long = 0,
    @ColumnInfo(name = "captured_at") val capturedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// RESOURCE EVENTS
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "resource_event",
    indices = [Index(value = ["event_at"])]
)
data class ResourceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "event_at") val eventAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// DEVICE HEALTH SNAPSHOT
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "device_health_snapshot",
    indices = [Index(value = ["captured_at"])]
)
data class DeviceHealthSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int = 0,
    @ColumnInfo(name = "is_charging") val isCharging: Boolean = false,
    @ColumnInfo(name = "available_storage_mb") val availableStorageMb: Long = 0,
    @ColumnInfo(name = "total_storage_mb") val totalStorageMb: Long = 0,
    @ColumnInfo(name = "available_ram_mb") val availableRamMb: Long = 0,
    @ColumnInfo(name = "thermal_status") val thermalStatus: Int = 0,
    @ColumnInfo(name = "captured_at") val capturedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// CONVERSATION
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "conversation_thread",
    indices = [Index(value = ["created_at"])]
)
data class ConversationThreadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title") val title: String = "New Conversation",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false
)

@Entity(
    tableName = "conversation_message",
    foreignKeys = [ForeignKey(
        entity = ConversationThreadEntity::class,
        parentColumns = ["id"],
        childColumns = ["thread_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["thread_id"]), Index(value = ["created_at"])]
)
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "role") val role: String, // "user" | "assistant" | "system"
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// COMMAND RECORD
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "command_record",
    indices = [Index(value = ["issued_at"]), Index(value = ["intent_name"])]
)
data class CommandRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_input") val rawInput: String,
    @ColumnInfo(name = "normalized_input") val normalizedInput: String = "",
    @ColumnInfo(name = "intent_name") val intentName: String = "",
    @ColumnInfo(name = "confidence") val confidence: Float = 0f,
    @ColumnInfo(name = "source") val source: CommandSource = CommandSource.USER_CHAT,
    @ColumnInfo(name = "thread_id") val threadId: Long? = null,
    @ColumnInfo(name = "issued_at") val issuedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// TASK EXECUTION
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "task_execution",
    indices = [Index(value = ["command_id"]), Index(value = ["status"])]
)
data class TaskExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "command_id") val commandId: Long,
    @ColumnInfo(name = "intent_name") val intentName: String,
    @ColumnInfo(name = "status") val status: TaskStatus = TaskStatus.PENDING,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)

@Entity(
    tableName = "task_step",
    foreignKeys = [ForeignKey(
        entity = TaskExecutionEntity::class,
        parentColumns = ["id"],
        childColumns = ["task_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["task_id"]), Index(value = ["step_index"])]
)
data class TaskStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "step_index") val stepIndex: Int,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "status") val status: StepStatus = StepStatus.PENDING,
    @ColumnInfo(name = "input_summary") val inputSummary: String = "",
    @ColumnInfo(name = "output_summary") val outputSummary: String = "",
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)

// ────────────────────────────────────────────────────────────────────────────
// POLICY DECISION
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "policy_decision",
    indices = [Index(value = ["command_id"]), Index(value = ["decided_at"])]
)
data class PolicyDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "command_id") val commandId: Long,
    @ColumnInfo(name = "intent_name") val intentName: String,
    @ColumnInfo(name = "risk_level") val riskLevel: RiskLevel = RiskLevel.LOW,
    @ColumnInfo(name = "allowed") val allowed: Boolean,
    @ColumnInfo(name = "reason") val reason: String = "",
    @ColumnInfo(name = "requires_confirmation") val requiresConfirmation: Boolean = false,
    @ColumnInfo(name = "decided_at") val decidedAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// CONFIRMATION RECORD
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "confirmation_record",
    indices = [Index(value = ["task_id"])]
)
data class ConfirmationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "prompt_shown") val promptShown: String = "",
    @ColumnInfo(name = "user_confirmed") val userConfirmed: Boolean? = null,
    @ColumnInfo(name = "prompted_at") val promptedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "responded_at") val respondedAt: Long? = null
)

// ────────────────────────────────────────────────────────────────────────────
// AUDIT LOG
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "audit_event",
    indices = [Index(value = ["occurred_at"]), Index(value = ["event_type"])]
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "actor") val actor: String = "AGENT",
    @ColumnInfo(name = "target_type") val targetType: String = "",
    @ColumnInfo(name = "target_id") val targetId: String = "",
    @ColumnInfo(name = "summary") val summary: String,
    // Sensitive details are never stored in plaintext
    @ColumnInfo(name = "redacted_details") val redactedDetails: String = "",
    @ColumnInfo(name = "risk_level") val riskLevel: RiskLevel = RiskLevel.NONE,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long = System.currentTimeMillis()
)

// ────────────────────────────────────────────────────────────────────────────
// KNOWLEDGE BASE
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "knowledge_source",
    indices = [Index(value = ["added_at"]), Index(value = ["source_type"])]
)
data class KnowledgeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "source_type") val sourceType: KnowledgeSourceType,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long = 0,
    @ColumnInfo(name = "index_status") val indexStatus: IndexStatus = IndexStatus.QUEUED,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_indexed_at") val lastIndexedAt: Long? = null
)

@Entity(
    tableName = "document",
    foreignKeys = [ForeignKey(
        entity = KnowledgeSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["source_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["source_id"]), Index(value = ["created_at"])]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "page_count") val pageCount: Int = 1,
    @ColumnInfo(name = "char_count") val charCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "text_chunk",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["document_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["document_id"]), Index(value = ["chunk_index"])]
)
data class TextChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "document_id") val documentId: Long,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "char_offset") val charOffset: Int = 0,
    @ColumnInfo(name = "page_number") val pageNumber: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// FTS4 virtual table — must mirror TextChunkEntity fields used for search
@Fts4(contentEntity = TextChunkEntity::class)
@Entity(tableName = "text_chunk_fts")
data class TextChunkFtsEntity(
    @ColumnInfo(name = "content") val content: String
)

@Entity(
    tableName = "embedding_vector",
    foreignKeys = [ForeignKey(
        entity = TextChunkEntity::class,
        parentColumns = ["id"],
        childColumns = ["chunk_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["chunk_id"], unique = true)]
)
data class EmbeddingVectorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chunk_id") val chunkId: Long,
    // Stored as serialized float array — reserved for future on-device embedding
    @ColumnInfo(name = "vector_blob") val vectorBlob: ByteArray? = null,
    @ColumnInfo(name = "model_version") val modelVersion: String = "none",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingVectorEntity) return false
        return id == other.id && chunkId == other.chunkId
    }
    override fun hashCode(): Int = 31 * id.hashCode() + chunkId.hashCode()
}

@Entity(
    tableName = "index_job",
    indices = [Index(value = ["source_id"]), Index(value = ["status"])]
)
data class IndexJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "status") val status: IndexStatus = IndexStatus.QUEUED,
    @ColumnInfo(name = "chunks_processed") val chunksProcessed: Int = 0,
    @ColumnInfo(name = "total_chunks") val totalChunks: Int = 0,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)

@Entity(
    tableName = "index_budget",
    indices = [Index(value = ["date"], unique = true)]
)
data class IndexBudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "date") val date: String, // YYYY-MM-DD
    @ColumnInfo(name = "bytes_indexed") val bytesIndexed: Long = 0,
    @ColumnInfo(name = "max_bytes") val maxBytes: Long = 50_000_000L // 50MB default daily budget
)

// ────────────────────────────────────────────────────────────────────────────
// SHARE BRIDGE
// ────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "shared_item",
    indices = [Index(value = ["received_at"])]
)
data class SharedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_type") val contentType: String, // "text" | "file" | "uri"
    @ColumnInfo(name = "raw_content") val rawContent: String = "",
    @ColumnInfo(name = "source_package") val sourcePackage: String = "",
    @ColumnInfo(name = "received_at") val receivedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "processed") val processed: Boolean = false
)

@Entity(
    tableName = "extracted_entity",
    foreignKeys = [ForeignKey(
        entity = SharedItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["shared_item_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["shared_item_id"]), Index(value = ["entity_type"])]
)
data class ExtractedEntityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "shared_item_id") val sharedItemId: Long,
    @ColumnInfo(name = "entity_type") val entityType: String, // date | time | phone | email | url | tracking_id
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "confidence") val confidence: Float = 1.0f,
    @ColumnInfo(name = "extracted_at") val extractedAt: Long = System.currentTimeMillis()
)
