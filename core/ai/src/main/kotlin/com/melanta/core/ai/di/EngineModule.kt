package com.melanta.core.ai.di

import com.melanta.core.ai.engine.LiteRtLmEngine
import com.melanta.core.ai.engine.LlmEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class EngineModule {
    @Binds
    abstract fun bindLlmEngine(impl: LiteRtLmEngine): LlmEngine
}
