package com.melanta.feature.agent

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.melanta.feature.agent.service.AgentForegroundService
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.AgentStateMachine
import com.melanta.feature.agent.state.AgentStep
import com.melanta.feature.agent.state.isActive
import com.melanta.feature.agent.state.isTerminal
import com.melanta.feature.agent.state.task
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Sobe o serviço em primeiro plano que roda o loop. Interface para o controller ser testável na JVM. */
fun interface AgentServiceLauncher {
    fun launch(task: String)
}

class ForegroundServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentServiceLauncher {
    override fun launch(task: String) {
        ContextCompat.startForegroundService(context, AgentForegroundService.startIntent(context, task))
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AgentControllerModule {
    @Binds
    abstract fun bindLauncher(impl: ForegroundServiceLauncher): AgentServiceLauncher
}

/**
 * Fonte única do [AgentState] e da linha do tempo de passos. A UI chama [start]/[stop]/[confirm];
 * o [AgentForegroundService] é dono do `Job` do loop e o registra aqui via [attach], para que
 * qualquer kill switch o cancele.
 */
@Singleton
class AgentController @Inject constructor(
    private val launcher: AgentServiceLauncher,
) {
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()

    @Volatile
    private var runJob: Job? = null

    @Volatile
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    /** Retorna `false` se já houver uma tarefa ativa ou se a tarefa estiver vazia. */
    fun start(task: String): Boolean {
        val trimmed = task.trim()
        if (trimmed.isEmpty()) return false
        if (_state.value.isTerminal) dismiss()
        if (!moveTo(AgentState.LoadingModel(trimmed))) return false
        _steps.value = emptyList()

        try {
            launcher.launch(trimmed)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: app em segundo plano.
            Log.w(TAG, "Não foi possível iniciar o AgentForegroundService", e)
            moveTo(AgentState.Failed(trimmed, "O Android não permitiu iniciar o agente agora."))
            return false
        }
        return true
    }

    /** Kill switch: cancela o loop imediatamente. Seguro de chamar de qualquer thread, quantas vezes for. */
    fun stop() {
        pendingConfirmation?.complete(false)
        runJob?.cancel(CancellationException("Kill switch"))
        transition { current -> current.task?.takeIf { current.isActive }?.let(AgentState::Cancelled) }
    }

    /** Resposta do usuário a um pedido de confirmação (diálogo, overlay ou notificação). */
    fun confirm(approved: Boolean) {
        pendingConfirmation?.complete(approved)
    }

    /** Volta para `Idle` depois que a UI exibiu o resultado. */
    fun dismiss() {
        transition { current -> AgentState.Idle.takeIf { current.isTerminal } }
    }

    internal fun attach(job: Job) {
        runJob = job
        job.invokeOnCompletion {
            if (runJob === job) runJob = null
        }
    }

    internal fun moveTo(next: AgentState): Boolean = transition { next }

    internal fun fail(reason: String) {
        transition { current -> current.task?.takeIf { current.isActive }?.let { AgentState.Failed(it, reason) } }
    }

    internal fun addStep(step: AgentStep) = _steps.update { it + step }

    internal fun updateStep(index: Int, transform: (AgentStep) -> AgentStep) =
        _steps.update { steps -> steps.map { if (it.index == index) transform(it) else it } }

    /**
     * Passa a `AwaitingConfirmation` e espera a resposta. Sem resposta em [timeoutMs], a ação é negada:
     * o padrão seguro é não fazer.
     */
    internal suspend fun requestConfirmation(task: String, step: Int, description: String, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = deferred
        try {
            if (!moveTo(AgentState.AwaitingConfirmation(task, step, description))) return false
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            pendingConfirmation = null
        }
    }

    private inline fun transition(next: (AgentState) -> AgentState?): Boolean {
        while (true) {
            val current = _state.value
            val target = next(current) ?: return false
            if (!AgentStateMachine.canTransition(current, target)) {
                Log.w(TAG, "Transição inválida: $current -> $target")
                return false
            }
            if (_state.compareAndSet(current, target)) return true
        }
    }

    private companion object {
        const val TAG = "AgentController"
    }
}
