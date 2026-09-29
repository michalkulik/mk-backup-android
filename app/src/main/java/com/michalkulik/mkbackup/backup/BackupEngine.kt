package com.michalkulik.mkbackup.backup

import android.content.Context
import com.michalkulik.mkbackup.core.BackupManifest
import com.michalkulik.mkbackup.core.BackupSet
import com.michalkulik.mkbackup.core.BackupStore
import com.michalkulik.mkbackup.core.ManifestEntry
import com.michalkulik.mkbackup.core.RunRecord
import com.michalkulik.mkbackup.core.RunStatus
import com.michalkulik.mkbackup.net.BackupClient
import com.michalkulik.mkbackup.net.FinishRequest
import com.michalkulik.mkbackup.net.StartSessionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Progress as reported to the UI and to the foreground notification. */
data class BackupProgress(
    val phase: Phase,
    val scanned: Int = 0,
    val hashed: Int = 0,
    val hashedTotal: Int = 0,
    val uploaded: Int = 0,
    val uploadTotal: Int = 0,
    val uploadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentPath: String = "",
) {
    enum class Phase { SCANNING, HASHING, UPLOADING, FINALISING }

    /** 0..100, or null while the amount of work is still unknown. */
    val percent: Int?
        get() = when (phase) {
            Phase.SCANNING -> null
            Phase.HASHING -> hashedTotal.takeIf { it > 0 }?.let { hashed * 100 / it }
            Phase.UPLOADING ->
                when {
                    uploadTotal > 0 -> uploaded * 100 / uploadTotal
                    totalBytes > 0 -> (uploadedBytes * 100 / totalBytes).toInt()
                    else -> null
                }

            Phase.FINALISING -> null
        }
}

/** Thrown when a run is stopped by the user or by WorkManager. */
class BackupCancelledException : CancellationException("Backup cancelled")

/**
 * Runs one incremental backup:
 *
 * 1. list every file under the selected folders,
 * 2. hash only the files whose size or timestamp changed since the last successful run,
 * 3. classify them into added / modified / deleted / unchanged,
 * 4. open a server session, upload just the changed files (gzipped on the wire),
 * 5. ask the server to fold the uploads into its tree and rebuild the single archive.
 *
 * The manifest is only written after the server confirmed the run, so a failure simply means the
 * next attempt re-uploads the same delta.
 */
class BackupEngine(
    context: Context,
    private val store: BackupStore,
    private val cancellation: BackupCancellation = BackupCancellation(),
) {

    private val appContext = context.applicationContext

    suspend fun run(
        set: BackupSet,
        onProgress: (BackupProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RunRecord = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val deviceName = store.deviceName()

        try {
            val client = BackupClient(
                set.serverUrl,
                set.token,
                folderId = store.serverDeviceId,
                legacyId = store::legacyDeviceId,
                previousIds = store.previousDeviceIds,
            )
            cancellation.attach(client)
            try {
                client.use { runInternal(set, client, deviceName, onProgress, isCancelled) }
            } finally {
                cancellation.detach()
            }
        } catch (cancelled: BackupCancelledException) {
            store.addRun(
                set.id,
                RunRecord(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    status = RunStatus.CANCELLED,
                    message = "Cancelled by the user",
                ),
            )
            throw cancelled
        } catch (error: Throwable) {
            // Aborting the OkHttp call reports as an IOException; the user asked for Stop, so the
            // run has to be recorded (and reported) as cancelled, not as a failure.
            if (cancellation.isCancelled) {
                store.addRun(
                    set.id,
                    RunRecord(
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        status = RunStatus.CANCELLED,
                        message = "Cancelled by the user",
                    ),
                )
                throw BackupCancelledException()
            }
            store.addRun(
                set.id,
                RunRecord(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    status = RunStatus.FAILED,
                    message = error.message ?: error.javaClass.simpleName,
                ),
            )
            throw error
        }
    }

    private fun runInternal(
        set: BackupSet,
        client: BackupClient,
        deviceName: String,
        onProgress: (BackupProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): RunRecord {
        val startedAt = System.currentTimeMillis()
        checkNotCancelled(isCancelled)

        // ---- 1. scan -------------------------------------------------------
        onProgress(BackupProgress(BackupProgress.Phase.SCANNING))
        val scanned = FileScanner.scan(appContext, set.folders, set.excludePatterns) { count ->
            onProgress(BackupProgress(BackupProgress.Phase.SCANNING, scanned = count))
        }
        checkNotCancelled(isCancelled)

        // ---- 2. diff base --------------------------------------------------
        var base = store.manifest(set.id)
        if (base.entries.isEmpty()) {
            // Fresh install: rebuild the base from what the server already holds, otherwise the
            // first run after a reinstall would upload everything again.
            base = runCatching {
                BackupManifest(set.id, entries = client.manifest(set.id))
            }.getOrDefault(base)
        }
        val previous: Map<String, ManifestEntry> = base.entries

        // ---- 3. hash only what can have changed ----------------------------
        val byPath = HashMap<String, ScannedFile>(scanned.size)
        val hashes = LinkedHashMap<String, String>(scanned.size)
        val toHash = ArrayList<ScannedFile>()
        scanned.forEach { file ->
            byPath[file.path] = file
            val entry = previous[file.path]
            if (DiffEngine.metadataMatches(entry, file.size, file.modified)) {
                // Size and timestamp still match, so the bytes cannot have changed: reuse the
                // digest instead of reading the file again.
                hashes[file.path] = entry!!.sha256
            } else {
                toHash += file
            }
        }
        toHash.forEachIndexed { index, file ->
            checkNotCancelled(isCancelled)
            onProgress(
                BackupProgress(
                    BackupProgress.Phase.HASHING,
                    hashed = index,
                    hashedTotal = toHash.size,
                    currentPath = file.path,
                ),
            )
            // A null stream means the document disappeared or the persisted permission was
            // revoked. That is a real failure, not a user cancellation, so it must surface as one.
            val digest = appContext.contentResolver.openInputStream(file.uri)?.use(Hashing::sha256)
                ?: throw IOException("Cannot read ${file.path} (the file is gone or access was revoked)")
            hashes[file.path] = digest
        }
        onProgress(
            BackupProgress(
                BackupProgress.Phase.HASHING,
                hashed = toHash.size,
                hashedTotal = toHash.size,
            ),
        )

        val diff = DiffEngine.classify(previous, hashes)
        val uploads = (diff.added + diff.modified).mapNotNull { byPath[it] }
        val uploadBytes = uploads.sumOf { if (it.size > 0) it.size else 0L }
        val modifiedPaths = diff.modified.toHashSet()

        // ---- 4. upload -----------------------------------------------------
        val session = client.startSession(
            StartSessionRequest(
                // Resolved here, not before: against an older server this is still the random id
                // the data has always lived under, so nothing is ever split across two folders.
                deviceId = client.deviceId(),
                deviceName = deviceName,
                setId = set.id,
                setName = set.name,
                baseVersion = base.version,
                keepVersions = set.keepVersions,
            ),
        )

        var uploadedBytes = 0L
        uploads.forEachIndexed { index, file ->
            checkNotCancelled(isCancelled)
            onProgress(
                BackupProgress(
                    BackupProgress.Phase.UPLOADING,
                    uploaded = index,
                    uploadTotal = uploads.size,
                    uploadedBytes = uploadedBytes,
                    totalBytes = uploadBytes,
                    currentPath = file.path,
                ),
            )
            client.uploadFile(
                sessionId = session.sessionId,
                path = file.path,
                sha256 = hashes.getValue(file.path),
                mode = if (file.path in modifiedPaths) "modified" else "added",
                openStream = {
                    appContext.contentResolver.openInputStream(file.uri)
                        ?: throw IOException("Cannot read ${file.path} (the file is gone or access was revoked)")
                },
                onBytes = { uploadedBytes += it },
            )
        }
        onProgress(
            BackupProgress(
                BackupProgress.Phase.UPLOADING,
                uploaded = uploads.size,
                uploadTotal = uploads.size,
                uploadedBytes = uploadedBytes,
                totalBytes = uploadBytes,
            ),
        )

        // ---- 5. let the server build the archive ---------------------------
        onProgress(BackupProgress(BackupProgress.Phase.FINALISING))
        val finished = client.finishSession(
            session.sessionId,
            FinishRequest(
                deleted = diff.deleted,
                keepVersions = set.keepVersions,
                createArchive = true,
            ),
        )

        // ---- 6. commit the new manifest ------------------------------------
        val now = System.currentTimeMillis()
        val entries = scanned.associate { file ->
            file.path to ManifestEntry(
                path = file.path,
                size = file.size,
                modified = file.modified,
                sha256 = hashes.getValue(file.path),
            )
        }
        store.saveManifest(
            BackupManifest(setId = set.id, version = finished.version, updatedAt = now, entries = entries),
        )

        val record = RunRecord(
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            status = RunStatus.SUCCESS,
            message = if (diff.hasChanges) "Uploaded ${uploads.size} file(s)" else "Nothing changed",
            addedFiles = diff.added.size,
            modifiedFiles = diff.modified.size,
            deletedFiles = diff.deleted.size,
            unchangedFiles = diff.unchanged.size,
            uploadedFiles = uploads.size,
            uploadedBytes = uploadedBytes,
            totalFiles = scanned.size,
            totalBytes = scanned.sumOf { if (it.size > 0) it.size else 0L },
            version = finished.version,
            archiveName = finished.archive.orEmpty(),
            archiveBytes = finished.archiveBytes,
        )
        store.addRun(set.id, record)
        return record
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled() || cancellation.isCancelled) throw BackupCancelledException()
    }
}
