package com.melanta.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.core.ai.download.DownloadStatus
import com.melanta.core.ai.download.ModelDownloads
import com.melanta.core.ai.model.DeviceMemory
import com.melanta.core.ai.model.ImportResult
import com.melanta.core.ai.model.InstalledModel
import com.melanta.core.ai.model.ModelCatalog
import com.melanta.core.ai.model.ModelImporter
import com.melanta.core.ai.model.ModelSpec
import com.melanta.core.ai.model.ModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ImportState {
    data object Idle : ImportState
    data class Copying(val copied: Long, val total: Long?) : ImportState
    data class Done(val result: ImportResult) : ImportState
}

/** Um modelo do catálogo e o estado dele neste aparelho. */
data class CatalogEntry(
    val spec: ModelSpec,
    val installed: InstalledModel?,
    val download: DownloadStatus,
)

data class ModelsUiState(
    val deviceRamGb: Int,
    val catalog: List<CatalogEntry>,
    val otherModels: List<InstalledModel>,
    val import: ImportState,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val store: ModelStore,
    private val downloads: ModelDownloads,
    private val importer: ModelImporter,
    deviceMemory: DeviceMemory,
) : ViewModel() {

    private val ramGb = deviceMemory.totalRamGb

    private val importState = MutableStateFlow<ImportState>(ImportState.Idle)

    private val downloadStates = combine(ModelCatalog.all.map(downloads::observe)) { it.toList() }

    val uiState: StateFlow<ModelsUiState> = combine(
        store.installed,
        downloadStates,
        importState,
    ) { installed, downloadList, import ->
        ModelsUiState(
            deviceRamGb = ramGb,
            catalog = ModelCatalog.all.mapIndexed { index, spec ->
                CatalogEntry(spec, installed.firstOrNull { it.spec == spec }, downloadList[index])
            },
            otherModels = installed.filter { it.spec == null },
            import = import,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ModelsUiState(
            deviceRamGb = ramGb,
            catalog = ModelCatalog.all.map { spec ->
                CatalogEntry(spec, store.installed.value.firstOrNull { it.spec == spec }, DownloadStatus.NotStarted)
            },
            otherModels = emptyList(),
            import = ImportState.Idle,
        ),
    )

    fun download(spec: ModelSpec) = downloads.enqueue(spec)

    fun cancelDownload(spec: ModelSpec) = downloads.cancel(spec)

    fun delete(installed: InstalledModel) = store.delete(installed)

    fun import(uri: Uri) {
        if (importState.value is ImportState.Copying) return
        importState.value = ImportState.Copying(0, null)
        viewModelScope.launch {
            val result = importer.import(uri) { copied, total -> importState.value = ImportState.Copying(copied, total) }
            importState.value = ImportState.Done(result)
        }
    }

}
