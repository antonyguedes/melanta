package com.melanta.core.ai.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Único ponto que libera o destino da nuvem: sem consentimento registrado para este destino, nenhum
 * pedido é montado. A UI pede o consentimento, mas é aqui que a regra vale.
 */
@Singleton
class CloudAccess @Inject constructor(
    private val settings: InferenceSettings,
    private val keys: ApiKeyStore,
) {
    /** Avisa quando uma chave muda: ela não faz parte das configurações. */
    val keyChanges: StateFlow<Int> get() = keys.revision

    suspend fun endpoint(): CloudEndpoint {
        val prefs = settings.current()
        problem(prefs)?.let { throw CloudNotReadyException(it) }
        return CloudEndpoint(prefs.cloud.baseUrl.trim(), prefs.cloud.model.trim(), keys.get(prefs.cloud.destinationId))
    }

    /** Para testar a conexão: envolve só a chave, sem nenhum conteúdo da tela. */
    fun connectionEndpoint(config: CloudConfig): CloudEndpoint {
        (config.problem() ?: missingKey(config))?.let { throw CloudNotReadyException(it) }
        return CloudEndpoint(config.baseUrl.trim(), config.model.trim(), keys.get(config.destinationId))
    }

    /** O que falta para usar a nuvem, em PT-BR, ou `null` se estiver pronta. */
    fun problem(prefs: InferencePreferences): String? =
        prefs.cloud.problem()
            ?: missingKey(prefs.cloud)
            ?: "Falta autorizar o envio da tela para ${prefs.cloud.provider.displayName}.".takeUnless { prefs.hasConsent }

    private fun missingKey(config: CloudConfig): String? =
        "Falta a chave de API de ${config.provider.displayName}.".takeIf {
            config.provider != CloudProvider.Custom && keys.get(config.destinationId) == null
        }
}

/** Confere uma vez por destino e chave, por sessão do app, se a nuvem responde antes de começar uma tarefa. */
@Singleton
class CloudPreflight @Inject constructor(
    private val access: CloudAccess,
    private val generator: CloudGenerator,
    private val network: NetworkStatus,
) {
    @Volatile private var validated: Int? = null

    suspend fun check() {
        val target = access.endpoint()
        if (!network.isOnline()) throw CloudUnavailableException("Sem conexão com a internet.")
        val fingerprint = listOf(target.baseUrl, target.apiKey).hashCode()
        if (validated == fingerprint) return
        generator.validate(target)
        validated = fingerprint
    }
}

@Singleton
class NetworkStatus @Inject constructor(@ApplicationContext private val context: Context) {
    fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
