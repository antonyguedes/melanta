package com.melanta.core.ai.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.melanta.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class InferenceMode { Local, Cloud }

data class InferencePreferences(
    val mode: InferenceMode = InferenceMode.Local,
    val cloud: CloudConfig = CloudConfig(),
    /** Destinos ([CloudConfig.destinationId]) que o usuário autorizou a receber o conteúdo da tela. */
    val consentedDestinations: Set<String> = emptySet(),
    val sendNotificationBodies: Boolean = false,
    /** Sem rede ou com o provedor fora do ar, usa o modelo local, se houver um instalado. */
    val localFallback: Boolean = false,
) {
    val hasConsent: Boolean get() = cloud.destinationId in consentedDestinations
}

interface InferenceSettings {
    val state: StateFlow<InferencePreferences>

    /** Valor persistido, mesmo que [state] ainda não tenha terminado de carregar. */
    suspend fun current(): InferencePreferences

    suspend fun update(transform: (InferencePreferences) -> InferencePreferences)
}

@Singleton
class DataStoreInferenceSettings private constructor(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) : InferenceSettings {

    @Inject
    constructor(@ApplicationContext context: Context, dispatchers: DispatcherProvider) : this(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("inference_settings") },
        CoroutineScope(SupervisorJob() + dispatchers.io),
    )

    override val state: StateFlow<InferencePreferences> =
        store.data.map(::read).stateIn(scope, SharingStarted.Eagerly, InferencePreferences())

    override suspend fun current() = read(store.data.first())

    override suspend fun update(transform: (InferencePreferences) -> InferencePreferences) {
        store.edit { prefs ->
            val next = transform(read(prefs))
            prefs[MODE] = next.mode.name
            prefs[PROVIDER] = next.cloud.provider.id
            prefs[BASE_URL] = next.cloud.baseUrl
            prefs[MODEL] = next.cloud.model
            prefs[CONSENTS] = next.consentedDestinations
            prefs[NOTIFICATION_BODIES] = next.sendNotificationBodies
            prefs[LOCAL_FALLBACK] = next.localFallback
        }
    }

    private fun read(prefs: Preferences): InferencePreferences {
        val provider = CloudProvider.fromId(prefs[PROVIDER])
        return InferencePreferences(
            mode = InferenceMode.entries.firstOrNull { it.name == prefs[MODE] } ?: InferenceMode.Local,
            cloud = CloudConfig(provider, prefs[BASE_URL] ?: provider.baseUrl, prefs[MODEL] ?: provider.suggestedModel),
            consentedDestinations = prefs[CONSENTS].orEmpty(),
            sendNotificationBodies = prefs[NOTIFICATION_BODIES] ?: false,
            localFallback = prefs[LOCAL_FALLBACK] ?: false,
        )
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val PROVIDER = stringPreferencesKey("provider")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val CONSENTS = stringSetPreferencesKey("consented_destinations")
        val NOTIFICATION_BODIES = booleanPreferencesKey("send_notification_bodies")
        val LOCAL_FALLBACK = booleanPreferencesKey("local_fallback")
    }
}
