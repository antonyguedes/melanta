package com.melanta.feature.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.core.ai.cloud.CloudAccess
import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.ai.cloud.InferencePreferences
import com.melanta.core.ai.cloud.InferenceSettings
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
    /** Modo Nuvem: [modelName] é o modelo remoto e fica `null` enquanto falta chave ou consentimento. */
    val cloud: Boolean = false,
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
    private val settings: InferenceSettings,
    private val cloudAccess: CloudAccess,
) : ViewModel() {

    init {
        // Carrega o modelo enquanto o usuário digita a tarefa: some a espera de segundos no primeiro passo.
        // Se nenhuma tarefa começar, o ModelManager descarrega sozinho depois de 60 s.
        viewModelScope.launch {
            if (settings.current().mode == InferenceMode.Local && modelManager.preferredModel() != null) {
                runCatching { preparer.prepare() }
            }
        }
    }

    val state: StateFlow<AgentUiState> = combine(
        combine(controller.state, controller.steps, ::Pair),
        store.installed,
        modelManager.state,
        bridge.isConnected,
        settings.state,
    ) { (agent, steps), _, engine, connected, inference ->
        AgentUiState(agent, steps, modelName(inference), engine, connected, inference.mode == InferenceMode.Cloud)
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

    private fun modelName(inference: InferencePreferences): String? = when (inference.mode) {
        InferenceMode.Local -> modelManager.preferredModel()?.displayName
        InferenceMode.Cloud -> inference.cloud.model.takeIf { cloudAccess.isReady(inference) }
    }

    fun start(task: String) = controller.start(task)

    fun stop() = controller.stop()

    fun confirm(approved: Boolean) = controller.confirm(approved)

    fun dismiss() = controller.dismiss()
}
