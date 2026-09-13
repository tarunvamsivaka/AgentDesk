package com.agentdesk.core.persistence.di

import android.content.Context
import androidx.room.Room
import com.agentdesk.core.persistence.dao.*
import com.agentdesk.core.persistence.db.AgentDeskDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AgentDeskDatabase {
        return Room.databaseBuilder(
            context,
            AgentDeskDatabase::class.java,
            "agentdesk.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideConsentDao(db: AgentDeskDatabase): ConsentDao = db.consentDao()

    @Provides
    fun providePermissionDao(db: AgentDeskDatabase): PermissionDao = db.permissionDao()

    @Provides
    fun provideCommandDao(db: AgentDeskDatabase): CommandDao = db.commandDao()

    @Provides
    fun provideTaskDao(db: AgentDeskDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideAuditDao(db: AgentDeskDatabase): AuditDao = db.auditDao()

    @Provides
    fun provideKnowledgeDao(db: AgentDeskDatabase): KnowledgeDao = db.knowledgeDao()

    @Provides
    fun provideDeviceDao(db: AgentDeskDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun provideFtsSearchDao(db: AgentDeskDatabase): FtsSearchDao = db.ftsSearchDao()

    @Provides
    fun providePolicyDecisionDao(db: AgentDeskDatabase): PolicyDecisionDao = db.policyDecisionDao()

    @Provides
    fun provideNoteDao(db: AgentDeskDatabase): NoteDao = db.noteDao()
}
