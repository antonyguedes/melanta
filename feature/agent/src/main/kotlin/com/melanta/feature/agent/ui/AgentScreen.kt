package com.melanta.feature.agent.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melanta.core.ai.engine.EngineState
import com.melanta.core.designsystem.MelantaColors
import com.melanta.core.designsystem.PlumButton
import com.melanta.core.designsystem.PlumGradientBox
import com.melanta.core.designsystem.SectionCard
import com.melanta.core.designsystem.StopButton
import com.melanta.feature.agent.R
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.AgentStep
import com.melanta.feature.agent.state.isActive
import com.melanta.feature.agent.state.isTerminal

@Composable
fun AgentRoute(
    onOpenModels: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenAutomation: () -> Unit,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    AgentScreen(
        state = state,
        onStart = { task ->
            // O agente opera o app que estava antes do Melanta (tela inicial ou o app de onde o
            // assistente foi chamado): a leitura de tela ignora as janelas do próprio Melanta.
            if (viewModel.start(task)) activity?.moveTaskToBack(true)
        },
        onStop = viewModel::stop,
        onConfirm = viewModel::confirm,
        onDismiss = viewModel::dismiss,
        onOpenModels = onOpenModels,
        onOpenPermissions = onOpenPermissions,
        onOpenPlayground = onOpenPlayground,
        onOpenAutomation = onOpenAutomation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScreen(
    state: AgentUiState,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenAutomation: () -> Unit,
) {
    var task by rememberSaveable { mutableStateOf("") }
    val agent = state.agent
    val listState = rememberLazyListState()
    LaunchedEffect(state.steps.size) {
        if (state.steps.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    Scaffold(
        containerColor = MelantaColors.TacticalBlack,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title), fontWeight = FontWeight.SemiBold, color = MelantaColors.Lilac) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MelantaColors.TacticalBlack),
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.agent_models))
                    }
                    IconButton(onClick = onOpenPermissions) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.agent_permissions))
                    }
                    ToolsMenu(onOpenPlayground, onOpenAutomation)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusHero(state) }

            if (!state.accessibilityConnected) {
                item {
                    SetupCard(stringResource(R.string.agent_setup_accessibility), stringResource(R.string.agent_permissions), onOpenPermissions)
                }
            }
            if (state.modelName == null) {
                item { SetupCard(stringResource(R.string.agent_setup_model), stringResource(R.string.agent_models), onOpenModels) }
            }

            item {
                when {
                    agent.isActive -> StopButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_stop))
                    }
                    else -> TaskInput(
                        task = task,
                        onTaskChange = { task = it },
                        enabled = state.canStart,
                        onSubmit = { if (task.isNotBlank()) onStart(task) },
                    )
                }
            }

            if (agent.isTerminal) {
                item {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_new_task)) }
                }
            }

            if (state.steps.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.agent_timeline),
                        style = MaterialTheme.typography.titleSmall,
                        color = MelantaColors.LilacMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(state.steps, key = { it.index }) { step -> StepRow(step) }
            }
        }
    }

    (agent as? AgentState.AwaitingConfirmation)?.let { awaiting ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = MelantaColors.SurfaceHigh,
            title = { Text(stringResource(R.string.agent_confirm_dialog_title)) },
            text = { Text(awaiting.description) },
            confirmButton = { PlumButton(onClick = { onConfirm(true) }) { Text(stringResource(R.string.agent_confirm_allow)) } },
            dismissButton = { TextButton(onClick = { onConfirm(false) }) { Text(stringResource(R.string.agent_confirm_deny)) } },
        )
    }
}

@Composable
private fun ToolsMenu(onOpenPlayground: () -> Unit, onOpenAutomation: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.agent_tools))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MelantaColors.SurfaceHigh) {
            DropdownMenuItem(text = { Text(stringResource(R.string.agent_playground)) }, onClick = {
                expanded = false
                onOpenPlayground()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.agent_automation)) }, onClick = {
                expanded = false
                onOpenAutomation()
            })
        }
    }
}

@Composable
private fun StatusHero(state: AgentUiState) {
    val agent = state.agent
    PlumGradientBox {
        Text(
            text = when (agent) {
                AgentState.Idle -> stringResource(R.string.agent_hero_idle)
                is AgentState.LoadingModel -> stringResource(R.string.agent_hero_loading)
                is AgentState.Running -> stringResource(R.string.agent_hero_running, agent.step + 1, phaseLabel(agent.phase))
                is AgentState.AwaitingConfirmation -> stringResource(R.string.agent_hero_confirm)
                is AgentState.Finished -> stringResource(R.string.agent_hero_finished)
                is AgentState.Failed -> stringResource(R.string.agent_hero_failed)
                is AgentState.Cancelled -> stringResource(R.string.agent_hero_cancelled)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MelantaColors.OnPlum,
            fontWeight = FontWeight.SemiBold,
        )
        val detail = when (agent) {
            is AgentState.LoadingModel -> agent.task
            is AgentState.Running -> agent.lastThought ?: agent.task
            is AgentState.AwaitingConfirmation -> agent.description
            is AgentState.Finished -> agent.message
            is AgentState.Failed -> agent.reason
            is AgentState.Cancelled -> agent.task
            AgentState.Idle -> null
        }
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MelantaColors.OnPlum.copy(alpha = 0.85f))
        }
        if (agent is AgentState.LoadingModel || agent is AgentState.Running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                color = MelantaColors.Lilac,
                trackColor = MelantaColors.PlumDeep,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(modelLabel(state), style = MaterialTheme.typography.labelMedium, color = MelantaColors.LilacMuted)
    }
}

@Composable
private fun modelLabel(state: AgentUiState): String {
    val name = state.modelName ?: return stringResource(R.string.agent_model_none)
    return when (state.engine) {
        is EngineState.Ready -> stringResource(R.string.agent_model_ready, name, (state.engine as EngineState.Ready).backend.name.uppercase())
        is EngineState.Loading -> stringResource(R.string.agent_model_loading, name)
        is EngineState.Error -> stringResource(R.string.agent_model_error, name)
        EngineState.Unloaded -> stringResource(R.string.agent_model_idle, name)
    }
}

@Composable
private fun phaseLabel(phase: AgentState.Phase): String = stringResource(
    when (phase) {
        AgentState.Phase.Perceiving -> R.string.agent_phase_perceiving
        AgentState.Phase.Reasoning -> R.string.agent_phase_reasoning
        AgentState.Phase.Acting -> R.string.agent_phase_acting
    },
)

@Composable
private fun TaskInput(task: String, onTaskChange: (String) -> Unit, enabled: Boolean, onSubmit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = task,
            onValueChange = onTaskChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.agent_task_placeholder)) },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MelantaColors.Lilac,
                unfocusedBorderColor = MelantaColors.Outline,
                focusedContainerColor = MelantaColors.SurfaceLow,
                unfocusedContainerColor = MelantaColors.SurfaceLow,
                disabledContainerColor = MelantaColors.SurfaceLow,
                cursorColor = MelantaColors.Lilac,
            ),
            trailingIcon = {
                IconButton(
                    onClick = onSubmit,
                    enabled = enabled && task.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MelantaColors.Plum,
                        contentColor = MelantaColors.OnPlum,
                        disabledContainerColor = MelantaColors.SurfaceHigh,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_start))
                }
            },
        )
        val suggestions = listOf(
            stringResource(R.string.agent_suggestion_alarm),
            stringResource(R.string.agent_suggestion_timer),
            stringResource(R.string.agent_suggestion_search),
            stringResource(R.string.agent_suggestion_app),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onTaskChange(suggestion) },
                    enabled = enabled,
                    label = { Text(suggestion) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MelantaColors.Surface, labelColor = MelantaColors.Text),
                    border = AssistChipDefaults.assistChipBorder(enabled = enabled, borderColor = MelantaColors.OutlineVariant),
                )
            }
        }
    }
}

@Composable
private fun SetupCard(message: String, action: String, onAction: () -> Unit) {
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MelantaColors.Lilac)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PlumButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun StepRow(step: AgentStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MelantaColors.Surface)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MelantaColors.PlumGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("${step.index + 1}", style = MaterialTheme.typography.labelMedium, color = MelantaColors.OnPlum)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(step.action, style = MaterialTheme.typography.bodyLarge, color = MelantaColors.Text)
            step.thought?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MelantaColors.TextMuted)
            }
            statusDetail(step.status)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MelantaColors.Danger)
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusIcon(step.status)
    }
}

@Composable
private fun statusDetail(status: AgentStep.Status): String? = when (status) {
    is AgentStep.Status.Failed -> status.reason
    is AgentStep.Status.Blocked -> status.reason
    AgentStep.Status.Denied -> stringResource(R.string.agent_step_denied)
    AgentStep.Status.AwaitingConfirmation -> stringResource(R.string.agent_step_awaiting)
    else -> null
}

@Composable
private fun StatusIcon(status: AgentStep.Status) {
    when (status) {
        AgentStep.Status.Running, AgentStep.Status.AwaitingConfirmation ->
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MelantaColors.Lilac)
        AgentStep.Status.Done -> Icon(Icons.Filled.Check, contentDescription = null, tint = MelantaColors.Success)
        is AgentStep.Status.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MelantaColors.Danger)
        is AgentStep.Status.Blocked -> Icon(Icons.Filled.Lock, contentDescription = null, tint = MelantaColors.Danger)
        AgentStep.Status.Denied -> Icon(Icons.Filled.Close, contentDescription = null, tint = MelantaColors.TextMuted)
    }
}
