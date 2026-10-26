package com.melanta.automation

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melanta.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationDebugRoute(
    onBack: () -> Unit,
    viewModel: AutomationDebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.automation_title)) },
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
            Text(stringResource(R.string.automation_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.json,
                onValueChange = viewModel::setJson,
                label = { Text(stringResource(R.string.automation_json)) },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::capture, enabled = !state.running) {
                    Text(stringResource(R.string.automation_capture))
                }
                Button(onClick = viewModel::execute, enabled = !state.running) {
                    Text(stringResource(R.string.automation_execute))
                }
                if (state.running) TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.playground_cancel)) }
            }
            state.countdown?.let {
                Text(stringResource(R.string.automation_countdown, it), style = MaterialTheme.typography.titleMedium)
            }
            if (state.output.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(state.output, style = mono, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
