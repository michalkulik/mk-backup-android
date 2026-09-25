package com.michalkulik.mkbackup.ui

import androidx.work.WorkInfo
import com.michalkulik.mkbackup.backup.BackupProgress
import com.michalkulik.mkbackup.work.BackupScheduler
import com.michalkulik.mkbackup.work.BackupWorker

/**
 * Helpers that interpret WorkManager's `WorkInfo` list. They are plain functions (not view model
 * methods) so a Compose screen can collect the list once and stay reactive.
 *
 * The distinction between the two kinds of work matters a great deal here. A **periodic** request is
 * reported as `ENQUEUED` for its whole life - that state simply means "scheduled", not "running" -
 * while a **one-off** request is `ENQUEUED` only while it is genuinely waiting for its constraints
 * to be satisfied. Treating a scheduled periodic job as an in-flight backup made the app claim
 * "backup in progress" forever, so the two are tagged and handled separately.
 */
object WorkState {

    private fun runs(infos: List<WorkInfo>, setId: String): List<WorkInfo> =
        infos.filter { BackupScheduler.runTag(setId) in it.tags }

    private fun scheduled(infos: List<WorkInfo>, setId: String): List<WorkInfo> =
        infos.filter { BackupScheduler.periodicTag(setId) in it.tags }

    /** True only while a worker is really executing, whichever kind of request started it. */
    fun isRunning(infos: List<WorkInfo>, setId: String): Boolean =
        (runs(infos, setId) + scheduled(infos, setId)).any { it.state == WorkInfo.State.RUNNING }

    /**
     * True when a run is executing or is queued and about to start. A periodic request that is
     * merely waiting for its next interval does not count.
     */
    fun isActive(infos: List<WorkInfo>, setId: String): Boolean =
        isRunning(infos, setId) || runs(infos, setId).any { it.state == WorkInfo.State.ENQUEUED }

    /** Progress of a currently executing run, or null when nothing is running. */
    fun progressOf(infos: List<WorkInfo>, setId: String): BackupProgress? =
        (runs(infos, setId) + scheduled(infos, setId))
            .firstOrNull { it.state == WorkInfo.State.RUNNING && it.progress.keyValueMap.isNotEmpty() }
            ?.progress
            ?.let { data ->
                BackupProgress(
                    phase = runCatching {
                        BackupProgress.Phase.valueOf(
                            data.getString(BackupWorker.KEY_PHASE) ?: BackupProgress.Phase.SCANNING.name,
                        )
                    }.getOrDefault(BackupProgress.Phase.SCANNING),
                    scanned = data.getInt(BackupWorker.KEY_SCANNED, 0),
                    hashed = data.getInt(BackupWorker.KEY_HASHED, 0),
                    hashedTotal = data.getInt(BackupWorker.KEY_HASHED_TOTAL, 0),
                    uploaded = data.getInt(BackupWorker.KEY_UPLOADED, 0),
                    uploadTotal = data.getInt(BackupWorker.KEY_UPLOAD_TOTAL, 0),
                    uploadedBytes = data.getLong(BackupWorker.KEY_UPLOADED_BYTES, 0),
                    totalBytes = data.getLong(BackupWorker.KEY_TOTAL_BYTES, 0),
                    currentPath = data.getString(BackupWorker.KEY_CURRENT_PATH).orEmpty(),
                )
            }
}
