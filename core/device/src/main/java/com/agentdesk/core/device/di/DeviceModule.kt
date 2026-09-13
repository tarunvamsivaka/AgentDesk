package com.agentdesk.core.device.di

import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.device.ResourceGovernor
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// DeviceProfiler and ResourceGovernor both use @Inject constructor + @Singleton.
// Hilt auto-generates their bindings. No manual @Provides needed.
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule
