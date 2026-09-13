package com.agentdesk.tools.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the :tools module.
 *
 * [ToolExecutor] uses @Singleton @Inject constructor, so Hilt auto-generates its binding.
 * This stub is kept for structural consistency and future @Provides additions.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule
