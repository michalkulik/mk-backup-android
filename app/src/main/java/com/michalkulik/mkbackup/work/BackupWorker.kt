package com.michalkulik.mkbackup.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.michalkulik.mkbackup.backup.BackupEngine
import com.michalkulik.mkbackup.backup.BackupProgress
import com.michalkulik.mkbackup.core.BackupStore
import com.michalkulik.mkbackup.core.RunRecord
import com.michalkulik.mkbackup.core.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes one backup set. It is scheduled either periodically (per set interval) or as a one-off
 * "run now" job.
 *
 * The worker promotes itself to a foreground job with the `dataSync` type so a long upload is not
 * interrupted when the phone goes idle.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val setId = inputData.getString(KEY_SET_ID) ?: return Result.failure()
        val store = BackupStore.get(applicationContext)
        val set = store.set(setId) ?: return Result.failure()

        if (!set.isRunnable) {
            store.addRun(
                setId,
                RunRecord(
                    startedAt = System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                    status = RunStatus.SKIPPED,
                    message = "No folders or no server configured",
                ),
            )
            return Result.success()
        }

        val engine = BackupEngine(applicationContext, store)
        // `setForeground`/`setProgress` are suspending, so they cannot be called from the engine's
        // plain progress callback. The callback only publishes into this StateFlow and a child
        // coroutine turns the newest value into notification + WorkInfo updates (throttled below).
        val progressFlow = MutableStateFlow(BackupProgress(BackupProgress.Phase.SCANNING))

        try {
            setForeground(foreground(progressFlow.value, set.name))
            val record = coroutineScope {
                val ticker = launch {
                    progressFlow.collectLatest { progress ->
                        // Restricting the update rate keeps the notification manager (and the
                        // system's WorkInfo bookkeeping) from being hammered on fast uploads.
                        delay(PROGRESS_INTERVAL_MS)
                        runCatching { setForeground(foreground(progress, set.name)) }
                        runCatching { setProgress(progress.workData()) }
                    }
                }
                try {
                    engine.run(
                        set = set,
                        onProgress = { progress -> progressFlow.value = progress },
                        isCancelled = { isStopped },
                    )
                } finally {
                    ticker.cancel()
                }
            }
            BackupNotifications.hideProgress(applicationContext)
            BackupNotifications.result(applicationContext, set.name, record)
            return Result.success()
        } catch (cancelled: CancellationException) {
            BackupNotifications.hideProgress(applicationContext)
            return Result.success()
        } catch (error: Throwable) {
            BackupNotifications.hideProgress(applicationContext)
            // The engine has already recorded the failure in the run history.
            BackupNotifications.result(
                applicationContext,
                set.name,
                store.runs(setId).firstOrNull()
                    ?: RunRecord(
                        startedAt = System.currentTimeMillis(),
                        finishedAt = System.currentTimeMillis(),
                        status = RunStatus.FAILED,
                        message = error.message ?: error.javaClass.simpleName,
                    ),
            )
            // Retry on transient problems (network, server down) rather than giving up silently.
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun foreground(progress: BackupProgress, setName: String): ForegroundInfo =
        withContext(Dispatchers.Default) {
            val notification = BackupNotifications.progress(applicationContext, setName, progress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    BackupNotifications.progressId(),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(BackupNotifications.progressId(), notification)
            }
        }

    private fun BackupProgress.workData() = workDataOf(
        KEY_PHASE to phase.name,
        KEY_SCANNED to scanned,
        KEY_HASHED to hashed,
        KEY_HASHED_TOTAL to hashedTotal,
        KEY_UPLOADED to uploaded,
        KEY_UPLOAD_TOTAL to uploadTotal,
        KEY_UPLOADED_BYTES to uploadedBytes,
        KEY_TOTAL_BYTES to totalBytes,
        KEY_CURRENT_PATH to currentPath,
    )

    companion object {
        const val KEY_SET_ID = "setId"
        const val KEY_PHASE = "phase"
        const val KEY_SCANNED = "scanned"
        const val KEY_HASHED = "hashed"
        const val KEY_HASHED_TOTAL = "hashedTotal"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_UPLOAD_TOTAL = "uploadTotal"
        const val KEY_UPLOADED_BYTES = "uploadedBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_CURRENT_PATH = "currentPath"
        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
