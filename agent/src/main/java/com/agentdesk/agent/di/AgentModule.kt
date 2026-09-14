package com.agentdesk.agent.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Hilt module for the :agent module.
 *
 * RuleEngine and PolicyEngine use @Singleton @Inject constructors,
 * so Hilt auto-generates their bindings. This module provides the
 * background dispatchers used by the command pipeline.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    fun provideCommandDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
