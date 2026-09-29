package com.michalkulik.mkbackup.net

import com.michalkulik.mkbackup.core.RemoteVersion
import kotlinx.serialization.Serializable

/** Wire format of the mk-backup server API. Keep in sync with `mk-backup-server/app/api.py`. */

@Serializable
data class StartSessionRequest(
    val deviceId: String,
    val deviceName: String,
    val setId: String,
    val setName: String,
    val baseVersion: Int,
    val keepVersions: Int,
    /**
     * Folder ids this device used before, newest first. The server moves the data across the
     * change (random id → readable device name, or a rename made in Settings) the first time it
     * sees them, so the version history is not left behind in an orphaned directory.
     */
    val previousDeviceIds: List<String> = emptyList(),
)

@Serializable
data class StartSessionResponse(
    val sessionId: String,
    val baseVersion: Int = 0,
)

@Serializable
data class UploadResponse(
    val path: String,
    val size: Long = 0,
    val sha256: String = "",
)

@Serializable
data class FinishRequest(
    val deleted: List<String> = emptyList(),
    val keepVersions: Int = 5,
    val createArchive: Boolean = true,
)

@Serializable
data class FinishResponse(
    val version: Int = 0,
    val archive: String? = null,
    val archiveBytes: Long = 0,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val createdAt: String = "",
)

@Serializable
data class VersionListResponse(
    val versions: List<RemoteVersion> = emptyList(),
)

@Serializable
data class ManifestFile(
    val path: String,
    val size: Long = 0,
    val sha256: String = "",
)

@Serializable
data class ManifestResponse(
    val version: Int = 0,
    val entries: List<ManifestFile> = emptyList(),
)

@Serializable
data class HealthResponse(
    val status: String = "unknown",
    val version: String = "",
)

@Serializable
data class ApiError(
    val detail: String = "",
)
