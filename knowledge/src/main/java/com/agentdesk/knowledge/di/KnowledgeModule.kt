package com.agentdesk.knowledge.di

import com.agentdesk.knowledge.vector.EmbeddingGenerator
import com.agentdesk.knowledge.vector.HashEmbeddingGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KnowledgeModule {

    @Provides
    @Singleton
    fun provideEmbeddingGenerator(): EmbeddingGenerator {
        return HashEmbeddingGenerator()
    }
}
