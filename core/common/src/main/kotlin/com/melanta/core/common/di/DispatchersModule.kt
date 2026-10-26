package com.melanta.core.common.di

import com.melanta.core.common.DefaultDispatcherProvider
import com.melanta.core.common.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DispatchersModule {
    @Binds
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider
}
