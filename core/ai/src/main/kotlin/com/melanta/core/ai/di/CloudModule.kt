package com.melanta.core.ai.di

import com.melanta.core.ai.cloud.ApiKeyStore
import com.melanta.core.ai.cloud.ApiKeyVault
import com.melanta.core.ai.cloud.CloudAccess
import com.melanta.core.ai.cloud.CloudGenerator
import com.melanta.core.ai.cloud.DataStoreInferenceSettings
import com.melanta.core.ai.cloud.InferenceSettings
import com.melanta.core.ai.engine.InferenceRouter
import com.melanta.core.ai.engine.ModelManager
import com.melanta.core.ai.engine.TextGenerator
import com.melanta.core.common.DispatcherProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CloudModule {
    @Binds abstract fun settings(impl: DataStoreInferenceSettings): InferenceSettings

    @Binds abstract fun keys(impl: ApiKeyVault): ApiKeyStore

    @Binds @Named(InferenceRouter.LOCAL) abstract fun local(impl: ModelManager): TextGenerator

    @Binds @Named(InferenceRouter.CLOUD) abstract fun cloud(impl: CloudGenerator): TextGenerator

    companion object {
        @Provides
        @Singleton
        fun cloudGenerator(access: CloudAccess, dispatchers: DispatcherProvider) =
            CloudGenerator(access::endpoint, dispatchers.io)
    }
}
