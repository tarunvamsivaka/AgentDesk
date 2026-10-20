package com.agentdesk.agent.di

import android.content.Context
import com.agentdesk.agent.intent.CascadingOnDeviceLlmManager
import com.agentdesk.agent.intent.DeterministicRuleFallback
import com.agentdesk.agent.intent.MlKitPromptApiProvider
import com.agentdesk.agent.intent.OnDeviceLlmManager
import com.agentdesk.agent.intent.OnnxLocalSlmProvider
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.device.DeviceProfiler
import com.agentdesk.core.device.ResourceGovernor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    fun provideCommandDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideMlKitPromptApiProvider(
        @ApplicationContext context: Context
    ): MlKitPromptApiProvider = MlKitPromptApiProvider(context)

    @Provides
    @Singleton
    fun provideOnnxLocalSlmProvider(
        @ApplicationContext context: Context,
        resourceGovernor: ResourceGovernor
    ): OnnxLocalSlmProvider = OnnxLocalSlmProvider(context, resourceGovernor)

    @Provides
    @Singleton
    fun provideDeterministicRuleFallback(
        ruleEngine: RuleEngine
    ): DeterministicRuleFallback = DeterministicRuleFallback(ruleEngine)

    @Provides
    @Singleton
    fun provideOnDeviceLlmManager(
        mlKitProvider: MlKitPromptApiProvider,
        onnxProvider: OnnxLocalSlmProvider,
        ruleFallback: DeterministicRuleFallback,
        resourceGovernor: ResourceGovernor,
        profiler: DeviceProfiler
    ): OnDeviceLlmManager = CascadingOnDeviceLlmManager(
        mlKitProvider = mlKitProvider,
        onnxProvider = onnxProvider,
        ruleFallback = ruleFallback,
        resourceGovernor = resourceGovernor,
        profiler = profiler
    )
}
