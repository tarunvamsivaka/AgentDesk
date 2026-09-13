package com.agentdesk.agent.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the :agent module.
 *
 * RuleEngine and PolicyEngine use @Singleton @Inject constructors,
 * so Hilt auto-generates their bindings. This stub exists to keep
 * the module structure consistent and allow future @Provides additions.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule
