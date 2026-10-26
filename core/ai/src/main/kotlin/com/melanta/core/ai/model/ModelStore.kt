package com.melanta.core.ai.model

import android.content.Context
import com.melanta.core.ai.download.HttpRangeDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dono do diretório `filesDir/models`. Arquivos parciais nunca aparecem em [installed]. */
@Singleton
class ModelStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    fun fileFor(spec: ModelSpec): File = File(dir, spec.fileName)

    fun partFileFor(spec: ModelSpec): File = File(dir, spec.fileName + PART_SUFFIX)

    fun isInstalled(spec: ModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() == spec.sizeBytes }

    fun freeBytes(): Long = dir.usableSpace

    /** Move um arquivo temporário já validado para o nome final, de forma atômica. */
    fun commit(temp: File, fileName: String): File {
        val dest = File(dir, fileName)
        if (!temp.renameTo(dest)) throw IOException("Falha ao mover ${temp.name} para ${dest.name}")
        File(temp.path + HttpRangeDownloader.CHECKPOINT_SUFFIX).delete()
        refresh()
        return dest
    }

    fun delete(model: InstalledModel) {
        model.file.delete()
        refresh()
    }

    fun deletePartial(spec: ModelSpec) {
        partFileFor(spec).delete()
        File(partFileFor(spec).path + HttpRangeDownloader.CHECKPOINT_SUFFIX).delete()
    }

    fun refresh() {
        _installed.value = scan()
    }

    private fun scan(): List<InstalledModel> =
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .sortedBy { it.name }
            .map { InstalledModel(file = it, sizeBytes = it.length(), spec = ModelCatalog.byFileName(it.name)) }

    companion object {
        /** `.litertlm` (LiteRT-LM), `.task`/`.bin` (formato legado do MediaPipe) e `.gguf` (llama.cpp, opcional). */
        val SUPPORTED_EXTENSIONS = setOf("task", "bin", "litertlm", "gguf")

        /** Folga mínima de armazenamento mantida livre após um download ou importação. */
        const val FREE_SPACE_MARGIN = 256L * 1024 * 1024

        const val PART_SUFFIX = ".part"
    }
}
