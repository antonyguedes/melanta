package com.melanta.feature.agent.loop

import com.melanta.core.ai.cloud.CloudException
import com.melanta.core.ai.cloud.CloudPreflight
import com.melanta.core.ai.cloud.CloudUnavailableException
import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.ai.cloud.InferenceSettings
import com.melanta.core.ai.cloud.NetworkStatus
import com.melanta.core.ai.cloud.withCloudRetry
import com.melanta.core.ai.engine.InsufficientMemoryException
import com.melanta.core.ai.engine.ModelManager
import com.melanta.core.ai.engine.ToolCallGenerator
import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.automation.action.ActionExecutor
import com.melanta.core.automation.action.ActionResult
import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.notifications.NotificationBuffer
import com.melanta.core.automation.screen.ScreenReader
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.service.UiIdleWatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Portas do loop do agente. O AgentOrchestrator só conhece estas interfaces, o que permite
 * testar o loop inteiro (limites, segurança, confirmação, cancelamento) na JVM com fakes.
 */

/** 1. perceber */
interface ScreenPerception {
    suspend fun snapshot(): ScreenSnapshot
    fun recentNotifications(): List<String>
}

/** 2. raciocinar */
fun interface ActionReasoner {
    suspend fun decide(input: PromptInput): ToolCallGenerator.Outcome
}

/** 4. agir + 5. esperar a tela estabilizar */
interface ActionActuator {
    suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot): ActionResult
    suspend fun awaitIdle(after: AgentAction): Boolean
}

/** Carrega o modelo preferido. Lança [AgentSetupException] com mensagem em PT-BR para o usuário. */
fun interface ModelPreparer {
    suspend fun prepare()
}

class AgentSetupException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DefaultScreenPerception @Inject constructor(
    private val reader: ScreenReader,
    private val notifications: NotificationBuffer,
) : ScreenPerception {
    /**
     * Logo após abrir um app a janela pode ainda não ter conteúdo: o modelo veria uma tela vazia
     * e tentaria abrir o app de novo. Relê por alguns instantes antes de desistir.
     */
    override suspend fun snapshot(): ScreenSnapshot {
        repeat(EMPTY_SCREEN_RETRIES) {
            val snapshot = reader.snapshot()
            if (snapshot.elements.isNotEmpty() || snapshot.protectedWindow != null) return snapshot
            delay(EMPTY_SCREEN_RETRY_MS)
        }
        return reader.snapshot()
    }

    override fun recentNotifications() = notifications.recentLines()

    private companion object {
        const val EMPTY_SCREEN_RETRIES = 8
        const val EMPTY_SCREEN_RETRY_MS = 400L
    }
}

class DefaultActionReasoner @Inject constructor(
    private val generator: ToolCallGenerator,
    private val modelManager: ModelManager,
    private val settings: InferenceSettings,
    private val network: NetworkStatus,
) : ActionReasoner {
    override suspend fun decide(input: PromptInput): ToolCallGenerator.Outcome =
        if (settings.current().mode == InferenceMode.Cloud) decideInCloud(input) else decideLocally(input)

    private suspend fun decideLocally(input: PromptInput): ToolCallGenerator.Outcome = try {
        generator.decide(input, constrained = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Falha na GPU: uma segunda chance na CPU. Em qualquer outro caso, o erro sobe para o loop.
        if (!modelManager.fallbackToCpu()) throw e
        generator.decide(input, constrained = true)
    }

    private suspend fun decideInCloud(input: PromptInput): ToolCallGenerator.Outcome = try {
        // Sem rede, as esperas do retry só atrasariam o fallback.
        if (!network.isOnline() && canFallBack()) throw CloudUnavailableException("Sem conexão com a internet.")
        withCloudRetry { generator.decide(input, constrained = true) }
    } catch (e: CloudUnavailableException) {
        if (!canFallBack()) throw AgentSetupException(e.message.orEmpty(), e)
        modelManager.loadPreferred()
        generator.decide(input, constrained = true, forceLocal = true)
    } catch (e: CloudException) {
        throw AgentSetupException(e.message.orEmpty(), e)
    }

    private suspend fun canFallBack() = settings.current().localFallback && modelManager.preferredModel() != null
}

class DefaultActionActuator @Inject constructor(
    private val executor: ActionExecutor,
    private val idleWatcher: UiIdleWatcher,
) : ActionActuator {
    override suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot) = executor.execute(action, snapshot)

    /** Abrir um app pode levar segundos (inicialização a frio); as demais ações assentam rápido. */
    override suspend fun awaitIdle(after: AgentAction) = if (after is AgentAction.LaunchIntent) {
        idleWatcher.awaitIdle(minSettleMs = LAUNCH_SETTLE_MS, timeoutMs = LAUNCH_TIMEOUT_MS)
    } else {
        idleWatcher.awaitIdle()
    }

    private companion object {
        const val LAUNCH_SETTLE_MS = 1_200L
        const val LAUNCH_TIMEOUT_MS = 5_000L
    }
}

class DefaultModelPreparer @Inject constructor(
    private val modelManager: ModelManager,
    private val settings: InferenceSettings,
    private val preflight: CloudPreflight,
) : ModelPreparer {
    override suspend fun prepare() {
        val current = settings.current()
        if (current.mode == InferenceMode.Local) {
            modelManager.loadPreferred()
            return
        }
        try {
            preflight.check()
        } catch (e: CloudUnavailableException) {
            if (!current.localFallback || modelManager.preferredModel() == null) throw AgentSetupException(e.message.orEmpty(), e)
            modelManager.loadPreferred()
        } catch (e: CloudException) {
            throw AgentSetupException(e.message.orEmpty(), e)
        }
    }
}

private suspend fun ModelManager.loadPreferred() {
    val model = preferredModel()
        ?: throw AgentSetupException("Nenhum modelo instalado. Baixe um modelo na tela Modelos.")
    try {
        ensureLoaded(model)
    } catch (e: CancellationException) {
        throw e
    } catch (e: InsufficientMemoryException) {
        throw AgentSetupException("Memória insuficiente para carregar ${model.displayName}. Feche outros apps e tente de novo.", e)
    } catch (e: Exception) {
        throw AgentSetupException("Não foi possível carregar ${model.displayName}: ${e.message}", e)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AgentPortsModule {
    @Binds abstract fun perception(impl: DefaultScreenPerception): ScreenPerception
    @Binds abstract fun reasoner(impl: DefaultActionReasoner): ActionReasoner
    @Binds abstract fun actuator(impl: DefaultActionActuator): ActionActuator
    @Binds abstract fun preparer(impl: DefaultModelPreparer): ModelPreparer
}
