package com.melanta.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melanta.R
import com.melanta.core.ai.cloud.CloudConfig
import com.melanta.core.ai.cloud.CloudProvider
import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.designsystem.MelantaColors
import com.melanta.core.designsystem.PlumButton
import com.melanta.core.designsystem.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineSettingsRoute(onBack: () -> Unit, viewModel: EngineSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs = state.preferences

    if (state.askingConsent) ConsentDialog(prefs.cloud, viewModel::acceptConsent, viewModel::declineConsent)

    Scaffold(
        containerColor = MelantaColors.TacticalBlack,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.engine_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.playground_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MelantaColors.TacticalBlack),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = stringResource(R.string.engine_mode_title)) {
                Choice(
                    title = stringResource(R.string.engine_mode_local),
                    detail = stringResource(R.string.engine_mode_local_detail),
                    selected = prefs.mode == InferenceMode.Local,
                    onClick = viewModel::selectLocal,
                )
                Choice(
                    title = stringResource(R.string.engine_mode_cloud),
                    detail = stringResource(R.string.engine_mode_cloud_detail),
                    selected = prefs.mode == InferenceMode.Cloud,
                    onClick = viewModel::selectCloud,
                )
            }

            SectionCard(title = stringResource(R.string.engine_provider_title)) {
                CloudProvider.entries.forEach { provider ->
                    Choice(provider.displayName, null, prefs.cloud.provider == provider) { viewModel.selectProvider(provider) }
                }
                if (prefs.cloud.provider == CloudProvider.Custom) {
                    OutlinedTextField(
                        value = prefs.cloud.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        label = { Text(stringResource(R.string.engine_base_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = prefs.cloud.model,
                    onValueChange = viewModel::setModel,
                    label = { Text(stringResource(R.string.engine_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ApiKeyField(state.hasKey, viewModel::saveKey, viewModel::removeKey)
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = state.test != ConnectionTest.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (state.test == ConnectionTest.Running) R.string.engine_testing else R.string.engine_test))
                }
                when (val test = state.test) {
                    is ConnectionTest.Ok -> Text(stringResource(R.string.engine_test_ok, test.millis), color = MelantaColors.Lilac)
                    is ConnectionTest.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }

            SectionCard(title = stringResource(R.string.engine_privacy_title)) {
                Toggle(
                    stringResource(R.string.engine_send_notifications),
                    stringResource(R.string.engine_send_notifications_detail),
                    prefs.sendNotificationBodies,
                    viewModel::setSendNotificationBodies,
                )
                Toggle(
                    stringResource(R.string.engine_fallback),
                    stringResource(R.string.engine_fallback_detail),
                    prefs.localFallback,
                    viewModel::setLocalFallback,
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit) {
    // A chave salva nunca volta para a tela: o campo só serve para digitar uma nova.
    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text(stringResource(R.string.engine_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (hasKey) {
        Text(stringResource(R.string.engine_api_key_saved), style = MaterialTheme.typography.bodySmall, color = MelantaColors.TextMuted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PlumButton(
            onClick = {
                onSave(typed)
                typed = ""
            },
            enabled = typed.isNotBlank(),
        ) { Text(stringResource(R.string.engine_save_key)) }
        if (hasKey) TextButton(onClick = onRemove) { Text(stringResource(R.string.engine_remove_key)) }
    }
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MelantaColors.TextMuted) }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MelantaColors.TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConsentDialog(config: CloudConfig, onAccept: () -> Unit, onDecline: () -> Unit) {
    val name = config.provider.displayName
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = MelantaColors.SurfaceHigh,
        title = { Text(stringResource(R.string.engine_consent_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.engine_consent_body, name, CloudConfig.host(config.baseUrl).orEmpty()))
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.engine_consent_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.engine_consent_decline)) } },
    )
}
