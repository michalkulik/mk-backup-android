package com.michalkulik.mkbackup.core

import kotlinx.serialization.Serializable

/**
 * One configured backup job: which folders to protect, where to send them, how often and how many
 * server-side versions to keep.
 *
 * Folders are stored as Storage Access Framework tree URIs (`content://…/tree/…`). Android grants
 * an app access to a directory only after the user picks it in the system picker, and SAF is the
 * supported way to reach the shared internal storage without the all-files permission.
 */
@Serializable
data class BackupSet(
    val id: String,
    val name: String,
    val folders: List<String> = emptyList(),
    val serverUrl: String = "",
    val token: String = "",
    val enabled: Boolean = true,
    val intervalHours: Int = 24,
    /** Preferred local hour (0-23) of the first run; the interval repeats from there. */
    val scheduleHour: Int = 3,
    val requireCharging: Boolean = false,
    val requireUnmetered: Boolean = false,
    val keepVersions: Int = 5,
    val excludePatterns: List<String> = emptyList(),
) {
    /** Everything needed to decide whether a run can actually be performed. */
    val isRunnable: Boolean
        get() = folders.isNotEmpty() && serverUrl.isNotBlank()
}

/** Result of a single backup run, kept locally so the UI can show a history. */
@Serializable
data class RunRecord(
    val startedAt: Long,
    val finishedAt: Long,
    val status: RunStatus,
    val message: String = "",
    val addedFiles: Int = 0,
    val modifiedFiles: Int = 0,
    val deletedFiles: Int = 0,
    val unchangedFiles: Int = 0,
    val uploadedFiles: Int = 0,
    val uploadedBytes: Long = 0,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val version: Int = 0,
    val archiveName: String = "",
    val archiveBytes: Long = 0,
)

@Serializable
enum class RunStatus { SUCCESS, FAILED, CANCELLED, SKIPPED }

/** A file as it exists in the last successful backup. */
@Serializable
data class ManifestEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val sha256: String,
)

/**
 * Snapshot of everything that was backed up in the last successful run. It is the base for the next
 * increment: files whose size and modification time still match are not re-read at all.
 */
@Serializable
data class BackupManifest(
    val setId: String,
    val version: Int = 0,
    val updatedAt: Long = 0,
    val entries: Map<String, ManifestEntry> = emptyMap(),
) {
    companion object {
        fun empty(setId: String) = BackupManifest(setId = setId)
    }
}

/** Server-side version of one backup set, as reported by the backend. */
@Serializable
data class RemoteVersion(
    val version: Int,
    val archive: String? = null,
    val archiveBytes: Long = 0,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val createdAt: String = "",
)
