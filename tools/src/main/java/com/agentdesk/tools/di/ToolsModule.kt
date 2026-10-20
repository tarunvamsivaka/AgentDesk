package com.agentdesk.tools.di

import com.agentdesk.core.common.tools.ToolRunner
import com.agentdesk.tools.executor.ToolExecutor
import com.agentdesk.tools.impl.AlarmTimerTool
import com.agentdesk.tools.impl.AppLaunchTool
import com.agentdesk.tools.impl.CalendarEventTool
import com.agentdesk.tools.impl.CommunicationTool
import com.agentdesk.tools.impl.QuickNoteTool
import com.agentdesk.tools.impl.SystemSettingsTool
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
 * used by CommandGateway.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @IntoMap
    @StringKey("open_app")
    fun provideAppLaunchTool(tool: AppLaunchTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("set_alarm")
    fun provideSetAlarmTool(tool: AlarmTimerTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("set_timer")
    fun provideSetTimerTool(tool: AlarmTimerTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("alarm_timer")
    fun provideAlarmTimerTool(tool: AlarmTimerTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("create_calendar_event")
    fun provideCalendarEventTool(tool: CalendarEventTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("calendar_create_event")
    fun provideCalendarCreateEventTool(tool: CalendarEventTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("draft_sms")
    fun provideDraftSmsTool(tool: CommunicationTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("open_dialer")
    fun provideOpenDialerTool(tool: CommunicationTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("draft_email")
    fun provideDraftEmailTool(tool: CommunicationTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("send_email")
    fun provideSendEmailTool(tool: CommunicationTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("communication")
    fun provideCommunicationTool(tool: CommunicationTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("create_note")
    fun provideCreateNoteTool(tool: QuickNoteTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("append_note")
    fun provideAppendNoteTool(tool: QuickNoteTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("search_notes")
    fun provideSearchNotesTool(tool: QuickNoteTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("quick_note")
    fun provideQuickNoteTool(tool: QuickNoteTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("system_settings")
    fun provideSystemSettingsTool(tool: SystemSettingsTool): Tool = tool

    @Provides
    @IntoMap
    @StringKey("open_settings")
    fun provideOpenSettingsTool(tool: SystemSettingsTool): Tool = tool

    @Provides
    @Singleton
    fun provideToolRunner(executor: ToolExecutor): ToolRunner = executor
}

