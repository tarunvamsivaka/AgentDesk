package com.agentdesk.core.settings.di

import com.agentdesk.core.settings.repository.ConsentRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// ConsentRepository is @Inject constructor -- Hilt auto-generates its binding.
// This file is reserved for additional manual bindings if needed in the future.
// No manual @Module needed for ConsentRepository since it uses constructor injection.
