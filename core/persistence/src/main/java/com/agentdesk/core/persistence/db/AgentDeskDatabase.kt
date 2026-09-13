package com.agentdesk.core.persistence.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.agentdesk.core.persistence.converter.AppConverters
import com.agentdesk.core.persistence.dao.*
import com.agentdesk.core.persistence.entity.*

@Database(
    entities = [
        UserProfileEntity::class,
        ConsentRecordEntity::class,
        PermissionStateEntity::class,
        UserPreferenceEntity::class,
        FeatureFlagOverrideEntity::class,
        DeviceCapabilitySnapshotEntity::class,
        ResourceEventEntity::class,
        DeviceHealthSnapshotEntity::class,
        ConversationThreadEntity::class,
        ConversationMessageEntity::class,
        CommandRecordEntity::class,
        TaskExecutionEntity::class,
        TaskStepEntity::class,
        PolicyDecisionEntity::class,
        ConfirmationRecordEntity::class,
        AuditEventEntity::class,
        KnowledgeSourceEntity::class,
        DocumentEntity::class,
        TextChunkEntity::class,
        TextChunkFtsEntity::class,
        EmbeddingVectorEntity::class,
        IndexJobEntity::class,
        IndexBudgetEntity::class,
        SharedItemEntity::class,
        ExtractedEntityEntity::class,
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(AppConverters::class)
abstract class AgentDeskDatabase : RoomDatabase() {
    abstract fun consentDao(): ConsentDao
    abstract fun permissionDao(): PermissionDao
    abstract fun commandDao(): CommandDao
    abstract fun taskDao(): TaskDao
    abstract fun auditDao(): AuditDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun deviceDao(): DeviceDao
    abstract fun ftsSearchDao(): FtsSearchDao
    abstract fun policyDecisionDao(): PolicyDecisionDao
    abstract fun confirmationDao(): ConfirmationDao
}
