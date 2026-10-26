package com.melanta.core.ai.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.melanta.core.ai.download.ModelDownloadWorker.FailureReason
import com.melanta.core.ai.model.ModelSpec
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface DownloadStatus {
    data object NotStarted : DownloadStatus
    /** Aguardando rede/armazenamento ou backoff entre tentativas. */
    data object Queued : DownloadStatus
    data class Running(val downloaded: Long, val total: Long?, val verifying: Boolean) : DownloadStatus
    data object Succeeded : DownloadStatus
    data class Failed(val reason: FailureReason?) : DownloadStatus
}

@Singleton
class ModelDownloads @Inject constructor(
    private val workManager: WorkManager,
) {
    fun enqueue(spec: ModelSpec) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to spec.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(uniqueName(spec), ExistingWorkPolicy.KEEP, request)
    }

    /** Cancela, mas mantém o `.part`: um novo [enqueue] retoma do ponto atual. */
    fun cancel(spec: ModelSpec) {
        workManager.cancelUniqueWork(uniqueName(spec))
    }

    fun observe(spec: ModelSpec): Flow<DownloadStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(spec))
            .map { infos -> infos.firstOrNull()?.toStatus() ?: DownloadStatus.NotStarted }

    private fun WorkInfo.toStatus(): DownloadStatus = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadStatus.Queued
        WorkInfo.State.RUNNING -> DownloadStatus.Running(
            downloaded = progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0L),
            total = progress.getLong(ModelDownloadWorker.KEY_TOTAL, -1L).takeIf { it > 0 },
            verifying = progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
        )
        WorkInfo.State.SUCCEEDED -> DownloadStatus.Succeeded
        WorkInfo.State.FAILED -> DownloadStatus.Failed(
            outputData.getString(ModelDownloadWorker.KEY_FAILURE)
                ?.let { name -> FailureReason.entries.firstOrNull { it.name == name } },
        )
        WorkInfo.State.CANCELLED -> DownloadStatus.NotStarted
    }

    private fun uniqueName(spec: ModelSpec) = "model-download-${spec.id}"
}
