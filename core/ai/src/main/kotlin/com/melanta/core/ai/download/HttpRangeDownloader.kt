package com.melanta.core.ai.download

import com.melanta.core.common.DispatcherProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class HttpStatusException(val code: Int) : IOException("HTTP $code") {
    /** Erros 4xx não melhoram com nova tentativa, exceto timeout, range inválido e rate limit. */
    val isPermanent: Boolean get() = code in 400..499 && code !in RETRYABLE_CLIENT_ERRORS

    private companion object {
        val RETRYABLE_CLIENT_ERRORS = setOf(408, 416, 429)
    }
}

/**
 * Download em arquivo único com retomada via HTTP Range.
 *
 * Se [target] já tiver bytes, pede `Range: bytes=<tamanho>-` e anexa. Se o servidor
 * ignorar o Range (200), recomeça do zero. Um 416 apaga o parcial para a próxima tentativa
 * recomeçar limpa.
 */
class HttpRangeDownloader @Inject constructor(
    private val dispatchers: DispatcherProvider,
) {
    suspend fun download(
        url: String,
        target: File,
        expectedSize: Long?,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Unit = withContext(dispatchers.io) {
        rollbackToCheckpoint(target)
        var existing = if (target.isFile) target.length() else 0L
        if (expectedSize != null) {
            if (existing == expectedSize) {
                onProgress(existing, expectedSize)
                return@withContext
            }
            if (existing > expectedSize) {
                target.delete()
                existing = 0L
            }
        }

        val connection = open(url, rangeStart = existing)
        try {
            val offset = when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val start = parseContentRangeStart(connection.getHeaderField("Content-Range"))
                    if (start != existing) {
                        target.delete()
                        throw IOException("Content-Range inesperado: começa em $start, esperado $existing")
                    }
                    existing
                }
                HttpURLConnection.HTTP_OK -> 0L
                else -> {
                    if (code == HTTP_RANGE_NOT_SATISFIABLE) target.delete()
                    throw HttpStatusException(code)
                }
            }
            val total = expectedSize ?: connection.contentLengthLong.takeIf { it >= 0 }?.plus(offset)

            var downloaded = offset
            var sinceCheckpoint = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target, offset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        sinceCheckpoint += read
                        if (sinceCheckpoint >= CHECKPOINT_BYTES) {
                            checkpoint(target, output, downloaded)
                            sinceCheckpoint = 0
                        }
                        val now = System.nanoTime()
                        if (now - lastReport >= PROGRESS_INTERVAL_NS) {
                            lastReport = now
                            onProgress(downloaded, total)
                        }
                    }
                    checkpoint(target, output, downloaded)
                }
            }
            onProgress(downloaded, total)
            if (total != null && downloaded != total) {
                throw IOException("Download incompleto: $downloaded de $total bytes")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Grava em disco o que já foi baixado e registra o tamanho confirmado. Sem isso, um desligamento
     * abrupto deixa no fim do `.part` bytes que nunca chegaram ao disco, e a retomada continuaria
     * depois deles: o SHA-256 só acusaria no final, perdendo o download inteiro.
     */
    private fun checkpoint(target: File, output: FileOutputStream, size: Long) {
        output.flush()
        output.fd.sync()
        checkpointFile(target).writeText(size.toString())
    }

    /** Volta o `.part` ao último tamanho confirmado em disco. Sem checkpoint, confia no tamanho atual. */
    private fun rollbackToCheckpoint(target: File) {
        val confirmed = checkpointFile(target).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return
        if (!target.isFile) return
        if (target.length() > confirmed) RandomAccessFile(target, "rw").use { it.setLength(confirmed) }
    }

    private fun checkpointFile(target: File) = File(target.path + CHECKPOINT_SUFFIX)

    private fun open(url: String, rangeStart: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Sem gzip transparente: o Content-Length precisa ser o do arquivo.
            setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
        }

    /** `bytes 100-999/1000` -> 100. */
    private fun parseContentRangeStart(header: String?): Long? =
        header?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    companion object {
        /** Arquivo ao lado do `.part` com o tamanho já gravado em disco com segurança. */
        const val CHECKPOINT_SUFFIX = ".ok"

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_INTERVAL_NS = 500_000_000L
        private const val CHECKPOINT_BYTES = 32L * 1024 * 1024
    }
}
