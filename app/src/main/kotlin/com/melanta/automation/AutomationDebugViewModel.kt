package com.melanta.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.feature.agent.debug.ManualActionRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutomationDebugUiState(
    val json: String = """{"tool":"scroll","args":{"dir":"down"}}""",
    val countdown: Int? = null,
    val running: Boolean = false,
    val output: String = "",
)

/** A contagem dá tempo de trocar para o app alvo: a leitura ignora as janelas do próprio Melanta. */
@HiltViewModel
class AutomationDebugViewModel @Inject constructor(
    private val runner: ManualActionRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(AutomationDebugUiState())
    val state: StateFlow<AutomationDebugUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun setJson(value: String) = _state.update { it.copy(json = value) }

    fun capture() = launchAfterCountdown { ManualActionRunner.dump(runner.snapshot()) }

    fun execute() = launchAfterCountdown {
        val report = runner.run(state.value.json)
        ManualActionRunner.dump(report.before) + "\n\n" + report.describe()
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(countdown = null, running = false) }
    }

    private fun launchAfterCountdown(block: suspend () -> String) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(running = true) }
            try {
                for (seconds in COUNTDOWN_SECONDS downTo 1) {
                    _state.update { it.copy(countdown = seconds) }
                    delay(1_000)
                }
                _state.update { it.copy(countdown = null) }
                val output = block()
                _state.update { it.copy(output = output) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(output = "erro: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(running = false, countdown = null) }
            }
        }
    }

    private companion object {
        const val COUNTDOWN_SECONDS = 5
    }
}
