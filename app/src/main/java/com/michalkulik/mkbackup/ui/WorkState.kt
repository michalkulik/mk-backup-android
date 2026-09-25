package com.michalkulik.mkbackup.ui

import androidx.work.WorkInfo
import com.michalkulik.mkbackup.backup.BackupProgress
import com.michalkulik.mkbackup.work.BackupScheduler
import com.michalkulik.mkbackup.work.BackupWorker

/**
 * Helpers that interpret WorkManager's `WorkInfo` list. They are plain functions (not view model
 * methods) so a Compose screen can collect the list once and stay reactive.
 */
object WorkState {

    private fun infosFor(infos: List<WorkInfo>, setId: String): List<WorkInfo> =
        infos.filter { BackupScheduler.setTag(setId) in it.tags }

    fun isRunning(infos: List<WorkInfo>, setId: String): Boolean =
        infosFor(infos, setId).any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    fun isActive(infos: List<WorkInfo>, setId: String): Boolean =
        infosFor(infos, setId).any {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }

    /** Progress of a currently executing run, or null when nothing is running. */
    fun progressOf(infos: List<WorkInfo>, setId: String): BackupProgress? =
        infosFor(infos, setId)
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
