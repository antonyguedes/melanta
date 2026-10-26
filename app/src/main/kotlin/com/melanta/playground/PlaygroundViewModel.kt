package com.melanta.playground

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.core.ai.engine.BackendPreference
import com.melanta.core.ai.engine.EngineState
import com.melanta.core.ai.engine.ModelManager
import com.melanta.core.ai.engine.ToolCallGenerator
import com.melanta.core.ai.eval.EvalScenarios
import com.melanta.core.ai.model.InstalledModel
import com.melanta.core.ai.model.ModelStore
import com.melanta.core.ai.prompt.PromptInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BenchmarkRow(
    val scenario: String,
    val expectedTool: String,
    val outcome: ToolCallGenerator.Outcome,
    val correct: Boolean,
) {
    val gotTool: String? get() = outcome.call?.name
}

data class PlaygroundForm(
    val scenarioIndex: Int = 0,
    val task: String,
    val history: String,
    val screen: String,
    val selectedModel: InstalledModel? = null,
    val backend: BackendPreference = BackendPreference.Auto,
    val constrained: Boolean = true,
)

data class PlaygroundRun(
    val running: Boolean = false,
    val liveAttempt: Int = 0,
    val liveOutput: String = "",
    val outcome: ToolCallGenerator.Outcome? = null,
    val benchmark: List<BenchmarkRow> = emptyList(),
    val benchmarkRunning: Boolean = false,
    val error: String? = null,
)

data class PlaygroundUiState(
    val models: List<InstalledModel>,
    val engine: EngineState,
    val form: PlaygroundForm,
    val run: PlaygroundRun,
)

@HiltViewModel
class PlaygroundViewModel @Inject constructor(
    store: ModelStore,
    private val modelManager: ModelManager,
    private val generator: ToolCallGenerator,
) : ViewModel() {

    private val form = MutableStateFlow(formFor(0).copy(selectedModel = modelManager.preferredModel()))
    private val run = MutableStateFlow(PlaygroundRun())
    private var job: Job? = null

    val uiState: StateFlow<PlaygroundUiState> =
        combine(store.installed, modelManager.state, form, run) { models, engine, form, run ->
            PlaygroundUiState(models, engine, form.copy(selectedModel = form.selectedModel ?: models.firstOrNull()), run)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PlaygroundUiState(store.installed.value, modelManager.state.value, form.value, run.value),
        )

    val scenarios = EvalScenarios.all

    fun selectScenario(index: Int) = form.update {
        formFor(index).copy(selectedModel = it.selectedModel, backend = it.backend, constrained = it.constrained)
    }

    fun setTask(value: String) = form.update { it.copy(task = value) }
    fun setHistory(value: String) = form.update { it.copy(history = value) }
    fun setScreen(value: String) = form.update { it.copy(screen = value) }
    fun selectModel(model: InstalledModel) = form.update { it.copy(selectedModel = model) }
    fun setBackend(value: BackendPreference) = form.update { it.copy(backend = value) }
    fun setConstrained(value: Boolean) = form.update { it.copy(constrained = value) }

    fun load() = launchExclusive { ensureLoaded() }

    fun unload() = launchExclusive { modelManager.unload() }

    fun generate() = launchExclusive {
        ensureLoaded() ?: return@launchExclusive
        val current = form.value
        val scenario = scenarios[current.scenarioIndex]
        val input = PromptInput(
            task = current.task,
            history = current.history.lines().filter { it.isNotBlank() },
            screen = scenario.screenContext(current.screen),
        )
        run.update { it.copy(outcome = null, liveOutput = "", liveAttempt = 0) }
        val outcome = generator.decide(input, current.constrained) { attempt, text ->
            run.update { it.copy(liveAttempt = attempt, liveOutput = text) }
        }
        run.update { it.copy(outcome = outcome) }
    }

    /** Roda todos os cenários com o modelo e as opções atuais. */
    fun benchmark() = launchExclusive {
        ensureLoaded() ?: return@launchExclusive
        run.update { it.copy(benchmark = emptyList(), benchmarkRunning = true) }
        try {
            for (scenario in scenarios) {
                val input = PromptInput(
                    task = scenario.task,
                    history = scenario.history,
                    screen = scenario.screenContext(),
                )
                run.update { it.copy(liveOutput = "", liveAttempt = 0) }
                val outcome = generator.decide(input, form.value.constrained) { attempt, text ->
                    run.update { it.copy(liveAttempt = attempt, liveOutput = text) }
                }
                val correct = outcome.call?.let(scenario.judge) ?: false
                run.update { it.copy(benchmark = it.benchmark + BenchmarkRow(scenario.name, scenario.expectedTool, outcome, correct)) }
            }
        } finally {
            run.update { it.copy(benchmarkRunning = false) }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun ensureLoaded(): Unit? {
        val current = uiState.value.form
        val model = current.selectedModel ?: run {
            run.update { it.copy(error = "Nenhum modelo instalado") }
            return null
        }
        modelManager.ensureLoaded(model, current.backend)
        return Unit
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            run.update { it.copy(running = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                run.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                run.update { it.copy(running = false) }
            }
        }
    }

    private fun formFor(index: Int): PlaygroundForm {
        val scenario = EvalScenarios.all[index]
        return PlaygroundForm(
            scenarioIndex = index,
            task = scenario.task,
            history = scenario.history.joinToString("\n"),
            screen = scenario.screen,
        )
    }
}
