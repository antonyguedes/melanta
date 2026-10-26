package com.melanta.core.ai.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.melanta.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface ImportResult {
    data class Success(val model: InstalledModel) : ImportResult
    data class UnsupportedFormat(val fileName: String) : ImportResult
    data class AlreadyExists(val fileName: String) : ImportResult
    data object NoSpace : ImportResult
    data class Failed(val message: String) : ImportResult
}

/** Copia um modelo escolhido via SAF para `filesDir/models`. O acesso ao URI não é persistido. */
@Singleton
class ModelImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ModelStore,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun import(
        uri: Uri,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): ImportResult = withContext(dispatchers.io) {
        val (displayName, size) = queryMetadata(uri)
        val fileName = sanitize(displayName ?: uri.lastPathSegment ?: "modelo")
        if (File(fileName).extension.lowercase() !in ModelStore.SUPPORTED_EXTENSIONS) {
            return@withContext ImportResult.UnsupportedFormat(fileName)
        }
        if (File(store.dir, fileName).exists()) return@withContext ImportResult.AlreadyExists(fileName)
        if (size != null && store.freeBytes() < size + ModelStore.FREE_SPACE_MARGIN) {
            return@withContext ImportResult.NoSpace
        }

        val temp = File(store.dir, fileName + IMPORT_SUFFIX)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Failed("Não foi possível abrir o arquivo")
            var copied = 0L
            input.use {
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, size)
                    }
                }
            }
            if (size != null && copied != size) {
                return@withContext ImportResult.Failed("Cópia incompleta: $copied de $size bytes")
            }
            val dest = store.commit(temp, fileName)
            ImportResult.Success(store.installed.value.first { it.file == dest })
        } catch (e: IOException) {
            ImportResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            name to size
        } ?: (null to null)

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')

    private companion object {
        const val IMPORT_SUFFIX = ".import"
        const val BUFFER_SIZE = 1024 * 1024
    }
}
