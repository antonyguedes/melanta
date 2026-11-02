package com.melanta.core.ai.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Único ponto que libera o destino da nuvem: sem consentimento registrado para este destino, nenhum
 * pedido é montado. A UI pede o consentimento, mas é aqui que a regra vale.
 */
@Singleton
class CloudAccess @Inject constructor(
    private val settings: InferenceSettings,
    private val keys: ApiKeyStore,
) {
    suspend fun endpoint(): CloudEndpoint {
        val prefs = settings.current()
        if (!prefs.hasConsent) {
            throw CloudNotReadyException("Autorize o envio da tela para ${prefs.cloud.provider.displayName} em Motor de IA.")
        }
        return connectionEndpoint(prefs.cloud)
    }

    /** Para testar a conexão: envolve só a chave, sem nenhum conteúdo da tela. */
    fun connectionEndpoint(config: CloudConfig): CloudEndpoint {
        config.problem()?.let { throw CloudNotReadyException(it) }
        val key = keys.get(config.destinationId)
        if (key == null && config.provider != CloudProvider.Custom) {
            throw CloudNotReadyException("Informe a chave de API de ${config.provider.displayName} em Motor de IA.")
        }
        return CloudEndpoint(config.baseUrl.trim(), config.model.trim(), key)
    }

    fun isReady(prefs: InferencePreferences): Boolean =
        prefs.hasConsent && prefs.cloud.problem() == null &&
            (prefs.cloud.provider == CloudProvider.Custom || keys.get(prefs.cloud.destinationId) != null)
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
