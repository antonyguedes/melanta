package com.melanta.feature.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.core.ai.engine.EngineState
import com.melanta.core.ai.engine.ModelManager
import com.melanta.core.ai.model.ModelStore
import com.melanta.core.automation.service.AccessibilityBridge
import com.melanta.feature.agent.AgentController
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.AgentStep
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.melanta.feature.agent.loop.ModelPreparer

data class AgentUiState(
    val agent: AgentState,
    val steps: List<AgentStep>,
    /** Modelo que será usado; `null` se nenhum estiver instalado. */
    val modelName: String?,
    val engine: EngineState,
    val accessibilityConnected: Boolean,
) {
    val canStart: Boolean get() = modelName != null && accessibilityConnected
}

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val controller: AgentController,
    private val modelManager: ModelManager,
    store: ModelStore,
    bridge: AccessibilityBridge,
    preparer: ModelPreparer,
) : ViewModel() {

    init {
        // Carrega o modelo enquanto o usuário digita a tarefa: some a espera de segundos no primeiro passo.
        // Se nenhuma tarefa começar, o ModelManager descarrega sozinho depois de 60 s.
        if (modelManager.preferredModel() != null) {
            viewModelScope.launch { runCatching { preparer.prepare() } }
        }
    }

    val state: StateFlow<AgentUiState> = combine(
        controller.state,
        controller.steps,
        store.installed,
        modelManager.state,
        bridge.isConnected,
    ) { agent, steps, _, engine, connected ->
        AgentUiState(agent, steps, modelManager.preferredModel()?.displayName, engine, connected)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgentUiState(
            controller.state.value,
            controller.steps.value,
            modelManager.preferredModel()?.displayName,
            modelManager.state.value,
            bridge.service.value != null,
        ),
    )

    fun start(task: String) = controller.start(task)

    fun stop() = controller.stop()

    fun confirm(approved: Boolean) = controller.confirm(approved)

    fun dismiss() = controller.dismiss()
}
