package com.melanta.models

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melanta.R
import com.melanta.core.ai.download.DownloadStatus
import com.melanta.core.ai.download.ModelDownloadWorker.FailureReason
import com.melanta.core.ai.model.ImportResult
import com.melanta.core.ai.model.InstalledModel
import com.melanta.core.ai.model.ModelCatalog
import com.melanta.core.ai.model.ModelSpec
import com.melanta.core.designsystem.MelantaColors
import com.melanta.core.designsystem.PlumButton
import com.melanta.core.designsystem.SectionCard

@Composable
fun ModelsRoute(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Modelos não têm MIME type registrado; o filtro por extensão fica no ModelImporter.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    ModelsScreen(
        state = state,
        onBack = onBack,
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onDeleteModel = viewModel::delete,
        onImport = { importLauncher.launch(arrayOf("*/*")) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsScreen(
    state: ModelsUiState,
    onBack: () -> Unit,
    onDownload: (ModelSpec) -> Unit,
    onCancelDownload: (ModelSpec) -> Unit,
    onDeleteModel: (InstalledModel) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)

    Scaffold(
        containerColor = MelantaColors.TacticalBlack,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
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
            Text(
                stringResource(R.string.home_model_device_ram, state.deviceRamGb),
                style = MaterialTheme.typography.bodyMedium,
                color = MelantaColors.TextMuted,
            )
            SectionCard(title = stringResource(R.string.models_catalog)) {
                state.catalog.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MelantaColors.OutlineVariant)
                    CatalogEntryRow(entry, state.deviceRamGb, ::size, onDownload, onCancelDownload, onDeleteModel)
                }
            }
            SectionCard(title = stringResource(R.string.models_import_section)) {
                Text(stringResource(R.string.models_import_body), style = MaterialTheme.typography.bodySmall, color = MelantaColors.TextMuted)
                when (val import = state.import) {
                    is ImportState.Copying -> {
                        Text(stringResource(R.string.home_model_importing, size(import.copied)))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = MelantaColors.Lilac, trackColor = MelantaColors.PlumDeep)
                    }
                    else -> {
                        if (import is ImportState.Done) ImportResultText(import.result)
                        OutlinedButton(onClick = onImport) { Text(stringResource(R.string.home_model_import)) }
                    }
                }
                state.otherModels.forEach { model ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${model.displayName} · ${size(model.sizeBytes)}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDeleteModel(model) }) { Text(stringResource(R.string.home_model_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogEntryRow(
    entry: CatalogEntry,
    deviceRamGb: Int,
    size: (Long) -> String,
    onDownload: (ModelSpec) -> Unit,
    onCancelDownload: (ModelSpec) -> Unit,
    onDeleteModel: (InstalledModel) -> Unit,
) {
    val spec = entry.spec
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (spec == ModelCatalog.default) {
                Text(stringResource(R.string.models_recommended), style = MaterialTheme.typography.labelMedium, color = MelantaColors.Lilac)
            }
        }
        val fits = deviceRamGb >= spec.recommendedRamGb
        Text(
            stringResource(if (fits) R.string.home_model_ram_ok else R.string.home_model_ram_low, spec.recommendedRamGb),
            style = MaterialTheme.typography.bodySmall,
            color = if (fits) MelantaColors.TextMuted else MelantaColors.Danger,
        )

        val download = entry.download
        val installed = entry.installed
        when {
            installed != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_model_installed, size(spec.sizeBytes)),
                    color = MelantaColors.Success,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDeleteModel(installed) }) { Text(stringResource(R.string.home_model_delete)) }
            }
            download is DownloadStatus.Running -> {
                val total = download.total
                if (download.verifying || total == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MelantaColors.Lilac, trackColor = MelantaColors.PlumDeep)
                } else {
                    LinearProgressIndicator(
                        progress = { download.downloaded.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                        color = MelantaColors.Lilac,
                        trackColor = MelantaColors.PlumDeep,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (download.verifying) {
                            stringResource(R.string.home_model_verifying)
                        } else {
                            stringResource(R.string.home_model_progress, size(download.downloaded), size(total ?: spec.sizeBytes))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCancelDownload(spec) }) { Text(stringResource(R.string.home_model_cancel)) }
                }
            }
            download is DownloadStatus.Queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_model_queued), modifier = Modifier.weight(1f))
                TextButton(onClick = { onCancelDownload(spec) }) { Text(stringResource(R.string.home_model_cancel)) }
            }
            else -> {
                if (download is DownloadStatus.Failed) {
                    Text(
                        stringResource(R.string.home_model_failed, stringResource(download.reason.label())),
                        color = MelantaColors.Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_model_not_installed, size(spec.sizeBytes)), modifier = Modifier.weight(1f))
                    PlumButton(onClick = { onDownload(spec) }) { Text(stringResource(R.string.home_model_download)) }
                }
            }
        }
    }
}

@Composable
private fun ImportResultText(result: ImportResult) {
    val (text, isError) = when (result) {
        is ImportResult.Success -> stringResource(R.string.home_import_ok, result.model.displayName) to false
        is ImportResult.UnsupportedFormat -> stringResource(R.string.home_import_unsupported, result.fileName) to true
        is ImportResult.AlreadyExists -> stringResource(R.string.home_import_exists, result.fileName) to true
        ImportResult.NoSpace -> stringResource(R.string.home_import_no_space) to true
        is ImportResult.Failed -> stringResource(R.string.home_import_failed, result.message) to true
    }
    Text(text, color = if (isError) MelantaColors.Danger else MelantaColors.Success)
}

private fun FailureReason?.label(): Int = when (this) {
    FailureReason.UnknownModel -> R.string.failure_unknown_model
    FailureReason.NoSpace -> R.string.failure_no_space
    FailureReason.ChecksumMismatch -> R.string.failure_checksum
    FailureReason.Http -> R.string.failure_http
    FailureReason.Network -> R.string.failure_network
    null -> R.string.failure_generic
}
