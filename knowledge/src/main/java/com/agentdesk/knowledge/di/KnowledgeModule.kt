package com.agentdesk.knowledge.di

import android.content.Context
import com.agentdesk.core.device.ResourceGovernor
import com.agentdesk.knowledge.vector.EmbeddingGenerator
import com.agentdesk.knowledge.vector.HashEmbeddingGenerator
import com.agentdesk.knowledge.vector.LiteRtEmbeddingGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KnowledgeModule {

    @Provides
    @Singleton
    fun provideEmbeddingGenerator(
        @ApplicationContext context: Context,
        resourceGovernor: ResourceGovernor
    ): EmbeddingGenerator {
        return LiteRtEmbeddingGenerator(
            context = context,
            resourceGovernor = resourceGovernor,
            fallbackGenerator = HashEmbeddingGenerator()
        )
    }
}
