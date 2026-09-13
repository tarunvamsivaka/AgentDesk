package com.agentdesk.knowledge.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Knowledge module dependencies injected via constructor injection.
// Reserved for future @Provides if custom factory or binding is needed.
@Module
@InstallIn(SingletonComponent::class)
object KnowledgeModule
