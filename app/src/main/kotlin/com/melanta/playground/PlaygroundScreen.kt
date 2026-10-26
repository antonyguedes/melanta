package com.melanta.playground

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melanta.R
import com.melanta.core.ai.engine.BackendPreference
import com.melanta.core.ai.engine.EngineState
import com.melanta.core.ai.engine.ToolCallGenerator
import com.melanta.core.ai.tool.ParseResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundRoute(
    onBack: () -> Unit,
    viewModel: PlaygroundViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val run = state.run

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playground_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.playground_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.playground_model)) {
                if (state.models.isEmpty()) Text(stringResource(R.string.playground_no_models))
                state.models.forEach { model ->
                    FilterChip(
                        selected = model == form.selectedModel,
                        onClick = { viewModel.selectModel(model) },
                        label = { Text(model.displayName, maxLines = 1) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendPreference.entries.forEach { backend ->
                        FilterChip(
                            selected = backend == form.backend,
                            onClick = { viewModel.setBackend(backend) },
                            label = { Text(backend.name.uppercase()) },
                        )
                    }
                }
                Text(engineLabel(state.engine), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::load, enabled = !run.running) {
                        Text(stringResource(R.string.playground_load))
                    }
                    OutlinedButton(onClick = viewModel::unload, enabled = !run.running) {
                        Text(stringResource(R.string.playground_unload))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.playground_constrained), modifier = Modifier.weight(1f))
                    Switch(checked = form.constrained, onCheckedChange = viewModel::setConstrained)
                }
            }

            Section(stringResource(R.string.playground_scenario)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    viewModel.scenarios.forEachIndexed { index, scenario ->
                        FilterChip(
                            selected = index == form.scenarioIndex,
                            onClick = { viewModel.selectScenario(index) },
                            label = { Text(scenario.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = form.task,
                    onValueChange = viewModel::setTask,
                    label = { Text(stringResource(R.string.playground_task)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.history,
                    onValueChange = viewModel::setHistory,
                    label = { Text(stringResource(R.string.playground_history)) },
                    textStyle = monoStyle(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.screen,
                    onValueChange = viewModel::setScreen,
                    label = { Text(stringResource(R.string.playground_screen)) },
                    textStyle = monoStyle(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::generate, enabled = !run.running) {
                    Text(stringResource(R.string.playground_generate))
                }
                OutlinedButton(onClick = viewModel::benchmark, enabled = !run.running) {
                    Text(stringResource(R.string.playground_benchmark))
                }
                if (run.running) {
                    TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.playground_cancel)) }
                }
            }
            if (run.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (run.running && run.liveOutput.isNotEmpty()) {
                Section(stringResource(R.string.playground_live, run.liveAttempt + 1)) {
                    Text(run.liveOutput, style = monoStyle())
                }
            }

            run.outcome?.let { OutcomeCard(it) }

            if (run.benchmark.isNotEmpty()) BenchmarkCard(run.benchmark, running = run.benchmarkRunning)
        }
    }
}

@Composable
private fun OutcomeCard(outcome: ToolCallGenerator.Outcome) {
    Section(stringResource(R.string.playground_result)) {
        Text(
            stringResource(
                R.string.playground_prompt_size,
                outcome.prompt.estimatedTokens,
                outcome.prompt.visibleIds.size,
                outcome.prompt.hiddenLines,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        outcome.attempts.forEachIndexed { index, attempt ->
            Text(stringResource(R.string.playground_attempt, index + 1), style = MaterialTheme.typography.titleSmall)
            Text(attempt.raw, style = monoStyle())
            when (val result = attempt.result) {
                is ParseResult.Success -> Text(
                    "✓ ${result.call.name} ${result.call.args}" + if (result.repaired) " (reparado)" else "",
                    color = MaterialTheme.colorScheme.primary,
                    style = monoStyle(),
                )
                is ParseResult.Failure -> Text(
                    "✗ ${result.error}: ${result.detail}",
                    color = MaterialTheme.colorScheme.error,
                    style = monoStyle(),
                )
            }
            Text(metricsLabel(attempt), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BenchmarkCard(rows: List<BenchmarkRow>, running: Boolean) {
    val correct = rows.count { it.correct }
    val valid = rows.count { it.outcome.call != null }
    val firstTry = rows.count { it.outcome.call != null && it.outcome.attempts.size == 1 }
    Section(stringResource(R.string.playground_benchmark_title)) {
        Text(
            stringResource(R.string.playground_benchmark_summary, valid, rows.size, firstTry, correct) +
                if (running) " …" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        rows.forEach { row ->
            val first = row.outcome.attempts.first()
            Text(
                "${if (row.correct) "✓" else "✗"} ${row.scenario}: ${row.gotTool ?: "inválido"} " +
                    "(esperado ${row.expectedTool}) · ${row.outcome.attempts.size} tent. · " +
                    "TTFT ${first.timeToFirstChunkMillis ?: "-"} ms · ${first.totalMillis} ms" +
                    (first.engineStats?.decodeTokensPerSecond?.let { " · %.1f tok/s".format(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun engineLabel(state: EngineState): String = when (state) {
    EngineState.Unloaded -> stringResource(R.string.playground_engine_unloaded)
    is EngineState.Loading -> stringResource(R.string.playground_engine_loading, state.modelFile.name)
    is EngineState.Ready -> stringResource(
        R.string.playground_engine_ready,
        state.backend.name.uppercase(),
        state.loadMillis / 1000.0,
    )
    is EngineState.Error -> stringResource(R.string.playground_engine_error, state.message)
}

private fun metricsLabel(attempt: ToolCallGenerator.Attempt): String = buildString {
    append("TTFT ").append(attempt.timeToFirstChunkMillis ?: "-").append(" ms · total ").append(attempt.totalMillis).append(" ms")
    attempt.engineStats?.let { stats ->
        stats.prefillTokens?.let { append(" · prefill ").append(it).append(" tok") }
        stats.prefillTokensPerSecond?.let { append(" @ %.0f tok/s".format(it)) }
        stats.decodeTokens?.let { append(" · decode ").append(it).append(" tok") }
        stats.decodeTokensPerSecond?.let { append(" @ %.1f tok/s".format(it)) }
        if (stats.cancelled) append(" · cortado")
    }
}

@Composable
private fun monoStyle() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
