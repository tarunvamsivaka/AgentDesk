package com.agentdesk.tools.di

import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.tools.executor.ToolExecutor
import com.agentdesk.tools.impl.AppLaunchTool
import com.agentdesk.tools.registry.Tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Singleton

/**
 * Hilt module for the :tools module.
 *
 * Registers concrete [Tool] classes into the tool map consumed by
 * [ToolExecutor], and exposes [ToolExecutor] as the app-wide [ToolRunner]
 * used by CommandGateway. Tools without a dedicated class (alarm, note,
 * library search, ...) are handled by [ToolExecutor] directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @IntoMap
    @StringKey("open_app")
    fun provideAppLaunchTool(tool: AppLaunchTool): Tool = tool

    @Provides
    @Singleton
    fun provideToolRunner(executor: ToolExecutor): ToolRunner = executor
}
