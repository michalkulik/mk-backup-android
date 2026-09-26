package com.michalkulik.mkbackup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michalkulik.mkbackup.R
import com.michalkulik.mkbackup.backup.BackupProgress
import com.michalkulik.mkbackup.core.BackupManifest
import com.michalkulik.mkbackup.core.BackupSet
import com.michalkulik.mkbackup.core.RunRecord
import com.michalkulik.mkbackup.core.RunStatus
import com.michalkulik.mkbackup.core.formatBytes
import com.michalkulik.mkbackup.core.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetDetailScreen(
    set: BackupSet,
    runs: List<RunRecord>,
    manifest: BackupManifest?,
    versions: VersionsState?,
    running: Boolean,
    progress: BackupProgress?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit,
    onCancel: () -> Unit,
    onRefreshVersions: () -> Unit,
    onDeleteVersion: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onResetManifest: () -> Unit,
) {
    var confirmDeleteSet by remember { mutableStateOf(false) }
    var confirmDeleteVersion by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        set.name.ifBlank { stringResource(R.string.set_unnamed) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                    }
                    IconButton(onClick = { confirmDeleteSet = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ------------------------------------------------------------ run
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (running) {
                            Text(
                                text = progressLabel(progress),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            val percent = progress?.percent
                            if (percent != null) {
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            progress?.currentPath?.takeIf { it.isNotBlank() }?.let { path ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.action_stop))
                            }
                        } else {
                            Text(
                                text = lastRunText(set, runs.firstOrNull()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onRunNow,
                                enabled = set.isRunnable,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.action_run_now))
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------- folders
            item {
                InfoCard(stringResource(R.string.section_folders)) {
                    if (set.folders.isEmpty()) {
                        Text(
                            stringResource(R.string.folders_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    set.folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                friendlyFolderName(folder),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // --------------------------------------------------------- server
            item {
                InfoCard(stringResource(R.string.section_server)) {
                    InfoRow(stringResource(R.string.field_server_url), set.serverUrl.ifBlank { "—" })
                    InfoRow(
                        stringResource(R.string.field_token),
                        if (set.token.isBlank()) stringResource(R.string.server_no_token) else "••••••",
                    )
                }
            }

            // ------------------------------------------------------- schedule
            item {
                InfoCard(stringResource(R.string.section_schedule)) {
                    InfoRow(stringResource(R.string.field_schedule), scheduleLabel(set.intervalHours))
                    InfoRow(stringResource(R.string.field_start_hour), formatHour(set.scheduleHour))
                    InfoRow(
                        stringResource(R.string.field_keep_versions),
                        stringResource(R.string.keep_versions_value, set.keepVersions),
                    )
                    if (set.requireCharging) {
                        InfoRow(stringResource(R.string.schedule_charging), stringResource(R.string.value_yes))
                    }
                    if (set.requireUnmetered) {
                        InfoRow(stringResource(R.string.schedule_unmetered), stringResource(R.string.value_yes))
                    }
                    if (set.excludePatterns.isNotEmpty()) {
                        InfoRow(
                            stringResource(R.string.section_exclusions),
                            set.excludePatterns.joinToString(", "),
                        )
                    }
                }
            }

            // ------------------------------------------------ local manifest
            item {
                val entries = manifest?.entries.orEmpty()
                InfoCard(stringResource(R.string.detail_local_state)) {
                    InfoRow(stringResource(R.string.detail_local_version), manifest?.version?.toString() ?: "0")
                    InfoRow(stringResource(R.string.detail_local_files), entries.size.toString())
                    InfoRow(
                        stringResource(R.string.detail_local_bytes),
                        formatBytes(entries.values.sumOf { it.size.coerceAtLeast(0) }),
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onResetManifest) {
                        Text(stringResource(R.string.action_reset_manifest))
                    }
                }
            }

            // ------------------------------------------------- remote list
            item {
                InfoCard(
                    title = stringResource(R.string.detail_remote_versions),
                    action = {
                        IconButton(onClick = onRefreshVersions) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                ) {
                    when (versions) {
                        null, is VersionsState.Loading -> Text(
                            stringResource(R.string.detail_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        is VersionsState.Failed -> Text(
                            stringResource(R.string.detail_error, versions.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        is VersionsState.Loaded ->
                            if (versions.versions.isEmpty()) {
                                Text(
                                    stringResource(R.string.detail_no_versions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                versions.versions.sortedByDescending { it.version }.forEach { version ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(R.string.version_label, version.version),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.version_details,
                                                    version.fileCount,
                                                    formatBytes(version.totalBytes),
                                                    formatBytes(version.archiveBytes),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { confirmDeleteVersion = version.version }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.action_delete),
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                    }
                }
            }

            // ------------------------------------------------------ history
            item {
                InfoCard(
                    title = stringResource(R.string.detail_history),
                    action = {
                        if (runs.isNotEmpty()) {
                            TextButton(onClick = onClearHistory) {
                                Text(stringResource(R.string.action_clear))
                            }
                        }
                    },
                ) {
                    if (runs.isEmpty()) {
                        Text(
                            stringResource(R.string.detail_no_runs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        runs.take(10).forEach { record ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    formatTimestamp(record.finishedAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    runDetail(record),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (record.status == RunStatus.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteSet) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSet = false },
            title = { Text(stringResource(R.string.delete_set_title)) },
            text = { Text(stringResource(R.string.delete_set_message, set.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteSet = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSet = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    confirmDeleteVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { confirmDeleteVersion = null },
            title = { Text(stringResource(R.string.delete_version_title)) },
            text = { Text(stringResource(R.string.delete_version_message, version)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteVersion = null
                        onDeleteVersion(version)
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteVersion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun progressLabel(progress: BackupProgress?): String = when (progress?.phase) {
    null -> stringResource(R.string.set_running)
    BackupProgress.Phase.SCANNING ->
        stringResource(R.string.notification_progress_scanning, progress.scanned)

    BackupProgress.Phase.HASHING ->
        stringResource(R.string.notification_progress_hashing, progress.hashed, progress.hashedTotal)

    BackupProgress.Phase.UPLOADING ->
        stringResource(
            R.string.notification_progress_uploading,
            progress.uploaded,
            progress.uploadTotal,
            formatBytes(progress.uploadedBytes),
        )

    BackupProgress.Phase.FINALISING -> stringResource(R.string.notification_progress_finalising)
}

@Composable
private fun runDetail(record: RunRecord): String = when (record.status) {
    RunStatus.SUCCESS ->
        stringResource(
            R.string.run_detail_success,
            record.uploadedFiles,
            formatBytes(record.uploadedBytes),
            record.addedFiles,
            record.modifiedFiles,
            record.deletedFiles,
            record.version,
        )

    RunStatus.FAILED -> stringResource(R.string.run_detail_failed, record.message)
    RunStatus.CANCELLED -> stringResource(R.string.run_detail_cancelled)
    RunStatus.SKIPPED -> stringResource(R.string.run_detail_skipped, record.message)
}
