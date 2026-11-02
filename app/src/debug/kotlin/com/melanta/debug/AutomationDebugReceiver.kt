package com.melanta.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.melanta.core.ai.cloud.ApiKeyStore
import com.melanta.core.ai.cloud.CloudConfig
import com.melanta.core.ai.cloud.CloudProvider
import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.ai.cloud.InferenceSettings
import com.melanta.core.automation.notifications.NotificationBuffer
import com.melanta.feature.agent.AgentController
import com.melanta.feature.agent.debug.ManualActionRunner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Painel de debug via adb (Fase 3). O resultado volta na própria saída do `am broadcast`:
 *
 *     adb shell am broadcast -n com.melanta/com.melanta.debug.AutomationDebugReceiver -a com.melanta.debug.DUMP
 *     adb shell am broadcast -n com.melanta/com.melanta.debug.AutomationDebugReceiver -a com.melanta.debug.EXEC \
 *         --es json '{"tool":"click","args":{"id":3}}'
 */
class AutomationDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication<DebugEntryPoint>(context)
        val runner = entryPoint.manualActionRunner()
        val notifications = entryPoint.notificationBuffer()
        val pending = goAsync()
        scope.launch {
            val output = try {
                when (intent.action) {
                    ACTION_DUMP -> ManualActionRunner.dump(runner.snapshot()) +
                        notifications.recentLines().takeIf { it.isNotEmpty() }?.joinToString(" | ", "\nNOTIFS: ").orEmpty()
                    ACTION_EXEC -> intent.getStringExtra(EXTRA_JSON)?.let { runner.run(it).describe() } ?: "faltou --es json"
                    ACTION_CONFIRM -> entryPoint.agentController().confirm(true).let { "confirmação: permitir" }
                    ACTION_INFERENCE -> setInference(entryPoint, intent)
                    ACTION_DENY -> entryPoint.agentController().confirm(false).let { "confirmação: negar" }
                    else -> "ação desconhecida"
                }
            } catch (e: Exception) {
                "erro: ${e.message ?: e.javaClass.simpleName}"
            }
            Log.i(TAG, output)
            pending.resultData = output
            pending.finish()
        }
    }

    /**
     * `--es base_url http://10.0.2.2:8123/v1 --es model fake --es key teste` configura a nuvem. Sem `base_url`,
     * `--es mode Cloud` ou `--es mode Local` só troca o modo, sem mexer em provedor nem chave (o padrão é Local).
     */
    private suspend fun setInference(entryPoint: DebugEntryPoint, intent: Intent): String {
        val baseUrl = intent.getStringExtra("base_url")
        val settings = entryPoint.inferenceSettings()
        // `--es forget custom|localhost --es provider gemini`: desfaz um teste, deixando a nuvem no provedor pedido.
        intent.getStringExtra("forget")?.let { destination ->
            entryPoint.apiKeys().remove(destination)
            val provider = CloudProvider.fromId(intent.getStringExtra("provider"))
            settings.update {
                it.copy(mode = InferenceMode.Cloud, cloud = CloudConfig(provider), consentedDestinations = it.consentedDestinations - destination)
            }
            return "esquecido: $destination; nuvem em ${provider.id}"
        }
        if (baseUrl == null) {
            val mode = if (intent.getStringExtra("mode") == "Cloud") InferenceMode.Cloud else InferenceMode.Local
            settings.update { it.copy(mode = mode) }
            return "modo: $mode"
        }
        val config = CloudConfig(CloudProvider.Custom, baseUrl, intent.getStringExtra("model").orEmpty())
        intent.getStringExtra("key")?.let { entryPoint.apiKeys().put(config.destinationId, it) }
        settings.update {
            it.copy(mode = InferenceMode.Cloud, cloud = config, consentedDestinations = it.consentedDestinations + config.destinationId)
        }
        return "modo: Nuvem ${config.destinationId}"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface DebugEntryPoint {
        fun manualActionRunner(): ManualActionRunner
        fun notificationBuffer(): NotificationBuffer
        fun agentController(): AgentController
        fun inferenceSettings(): InferenceSettings
        fun apiKeys(): ApiKeyStore
    }

    private companion object {
        const val TAG = "MelantaDebug"
        const val ACTION_DUMP = "com.melanta.debug.DUMP"
        const val ACTION_EXEC = "com.melanta.debug.EXEC"
        const val ACTION_CONFIRM = "com.melanta.debug.CONFIRM"
        const val ACTION_DENY = "com.melanta.debug.DENY"
        const val ACTION_INFERENCE = "com.melanta.debug.INFERENCE"
        const val EXTRA_JSON = "json"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
