package com.agentdesk.tools.di

import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.tools.executor.ToolExecutor
import com.agentdesk.tools.impl.AlarmTool
import com.agentdesk.tools.impl.AppLaunchTool
import com.agentdesk.tools.impl.NoteTool
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
 * Registers the three concrete tools into the tool map consumed by
 * [ToolExecutor], and exposes [ToolExecutor] as the app-wide [ToolRunner]
 * used by CommandGateway.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @IntoMap
    @StringKey("set_alarm")
    fun provideAlarmTool(tool: AlarmTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("create_note")
    fun provideNoteTool(tool: NoteTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("open_app")
    fun provideAppLaunchTool(tool: AppLaunchTool): Tool = tool

    @Provides
    @Singleton
    fun provideToolRunner(executor: ToolExecutor): ToolRunner = executor
}
