package com.agentdesk.core.security.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// AppLockManager and Redactor use @Inject constructor + @Singleton.
// Hilt auto-generates bindings -- no manual @Provides needed here.
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule
