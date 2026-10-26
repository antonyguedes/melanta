package com.melanta.core.ai.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.melanta.core.ai.R
import com.melanta.core.ai.model.ModelCatalog
import com.melanta.core.ai.model.ModelSpec
import com.melanta.core.ai.model.ModelStore
import com.melanta.core.common.DispatcherProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.withContext

/**
 * Baixa um [ModelSpec] do catálogo para `filesDir/models`.
 *
 * O arquivo `.part` sobrevive a falhas e cancelamentos, então cada nova tentativa
 * continua de onde parou (HTTP Range). O SHA-256 é conferido antes do arquivo final aparecer.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: ModelStore,
    private val downloader: HttpRangeDownloader,
    private val dispatchers: DispatcherProvider,
) : CoroutineWorker(context, params) {

    private var lastNotifiedPercent = -1

    override suspend fun doWork(): Result {
        val spec = inputData.getString(KEY_MODEL_ID)?.let(ModelCatalog::byId)
            ?: return failure(FailureReason.UnknownModel)
        if (store.isInstalled(spec)) return Result.success()

        val partFile = store.partFileFor(spec)
        val remaining = spec.sizeBytes - partFile.length()
        if (store.freeBytes() < remaining + ModelStore.FREE_SPACE_MARGIN) {
            return failure(FailureReason.NoSpace)
        }

        promoteToForeground(foregroundInfo(spec, partFile.length(), spec.sizeBytes))

        return try {
            downloader.download(spec.url, partFile, spec.sizeBytes) { downloaded, total ->
                setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to (total ?: -1L)))
                updateNotification(spec, downloaded, total)
            }

            setProgress(workDataOf(KEY_DOWNLOADED to spec.sizeBytes, KEY_TOTAL to spec.sizeBytes, KEY_VERIFYING to true))
            promoteToForeground(foregroundInfo(spec, spec.sizeBytes, spec.sizeBytes, verifying = true))
            val hash = withContext(dispatchers.io) { partFile.sha256Hex() }
            if (!hash.equals(spec.sha256, ignoreCase = true)) {
                Log.w(TAG, "SHA-256 divergente para ${spec.id}: $hash")
                store.deletePartial(spec)
                return failure(FailureReason.ChecksumMismatch)
            }

            store.commit(partFile, spec.fileName)
            Result.success()
        } catch (e: HttpStatusException) {
            Log.w(TAG, "HTTP ${e.code} ao baixar ${spec.id}")
            if (e.isPermanent) failure(FailureReason.Http) else retryOrFail(FailureReason.Http)
        } catch (e: IOException) {
            Log.w(TAG, "Falha de rede ao baixar ${spec.id}", e)
            retryOrFail(FailureReason.Network)
        }
    }

    private fun retryOrFail(reason: FailureReason): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else failure(reason)

    private fun failure(reason: FailureReason): Result =
        Result.failure(workDataOf(KEY_FAILURE to reason.name))

    /**
     * Sem FGS o download continua como trabalho comum (limite de ~10 min); a retomada por
     * Range cobre o restante. Acontece quando o worker reinicia com o app em segundo plano.
     */
    private suspend fun promoteToForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Não foi possível iniciar o FGS de download", e)
        }
    }

    private suspend fun updateNotification(spec: ModelSpec, downloaded: Long, total: Long?) {
        val percent = percentOf(downloaded, total)
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent
        promoteToForeground(foregroundInfo(spec, downloaded, total))
    }

    private fun foregroundInfo(
        spec: ModelSpec,
        downloaded: Long,
        total: Long?,
        verifying: Boolean = false,
    ): ForegroundInfo {
        ensureChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.model_download_title, spec.displayName))
            .setContentText(if (verifying) applicationContext.getString(R.string.model_download_verifying) else null)
            .setProgress(100, percentOf(downloaded, total), verifying || total == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, applicationContext.getString(R.string.model_download_cancel), cancel)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.model_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun percentOf(downloaded: Long, total: Long?): Int =
        if (total == null || total <= 0) 0 else (downloaded * 100 / total).toInt()

    enum class FailureReason { UnknownModel, NoSpace, ChecksumMismatch, Http, Network }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_FAILURE = "failure"

        private const val TAG = "ModelDownloadWorker"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 0x4D44 // "MD"
        private const val MAX_ATTEMPTS = 8
    }
}
