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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.michalkulik.mkbackup.R
import com.michalkulik.mkbackup.core.BackupSet
import com.michalkulik.mkbackup.core.RunRecord
import com.michalkulik.mkbackup.core.formatBytes
import com.michalkulik.mkbackup.core.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetListScreen(
    sets: List<BackupSet>,
    runs: Map<String, List<RunRecord>>,
    workInfos: List<WorkInfo>,
    onOpen: (BackupSet) -> Unit,
    onAdd: () -> Unit,
    onToggle: (BackupSet, Boolean) -> Unit,
    onRunNow: (BackupSet) -> Unit,
    onCancel: (BackupSet) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sets_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_set)) },
            )
        },
    ) { padding ->
        if (sets.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sets, key = { it.id }) { set ->
                    SetCard(
                        set = set,
                        lastRun = runs[set.id]?.firstOrNull(),
                        active = WorkState.isActive(workInfos, set.id),
                        progress = WorkState.progressOf(workInfos, set.id),
                        onOpen = { onOpen(set) },
                        onToggle = { onToggle(set, it) },
                        onRunNow = { onRunNow(set) },
                        onCancel = { onCancel(set) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.sets_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.sets_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetCard(
    set: BackupSet,
    lastRun: RunRecord?,
    active: Boolean,
    progress: com.michalkulik.mkbackup.backup.BackupProgress?,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = set.name.ifBlank { stringResource(R.string.set_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary(set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = set.enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = lastRunText(set, lastRun),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (active) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = progressLabel(progress),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                val percent = progress?.percent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (active) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_stop)) }
                } else {
                    TextButton(
                        onClick = onRunNow,
                        enabled = set.isRunnable,
                    ) { Text(stringResource(R.string.action_run_now)) }
                }
            }
        }
    }
}

@Composable
private fun summary(set: BackupSet): String {
    val folders = stringResource(R.string.set_summary_folders, set.folders.size)
    val schedule = if (set.enabled) scheduleLabel(set.intervalHours) else stringResource(R.string.set_disabled)
    return "$folders · $schedule"
}

@Composable
fun scheduleLabel(hours: Int): String = when (hours) {
    1 -> stringResource(R.string.schedule_every_hour)
    24 -> stringResource(R.string.schedule_daily)
    168 -> stringResource(R.string.schedule_weekly)
    else -> stringResource(R.string.schedule_every_hours, hours)
}

@Composable
fun lastRunText(set: BackupSet, lastRun: RunRecord?): String {
    if (lastRun == null) return stringResource(R.string.set_never_run)
    val when_ = formatTimestamp(lastRun.finishedAt)
    return when (lastRun.status) {
        com.michalkulik.mkbackup.core.RunStatus.SUCCESS ->
            stringResource(R.string.set_last_run_success, when_, lastRun.uploadedFiles, lastRun.version)

        com.michalkulik.mkbackup.core.RunStatus.FAILED ->
            stringResource(R.string.set_last_run_failed, when_, lastRun.message)

        com.michalkulik.mkbackup.core.RunStatus.CANCELLED ->
            stringResource(R.string.set_last_run_cancelled, when_)

        com.michalkulik.mkbackup.core.RunStatus.SKIPPED ->
            stringResource(R.string.set_last_run_skipped, when_, lastRun.message)
    }
}

@Composable
private fun progressLabel(progress: com.michalkulik.mkbackup.backup.BackupProgress?): String {
    if (progress == null) return stringResource(R.string.set_running)
    return when (progress.phase) {
        com.michalkulik.mkbackup.backup.BackupProgress.Phase.SCANNING ->
            stringResource(R.string.notification_progress_scanning, progress.scanned)

        com.michalkulik.mkbackup.backup.BackupProgress.Phase.HASHING ->
            stringResource(R.string.notification_progress_hashing, progress.hashed, progress.hashedTotal)

        com.michalkulik.mkbackup.backup.BackupProgress.Phase.UPLOADING ->
            stringResource(
                R.string.notification_progress_uploading,
                progress.uploaded,
                progress.uploadTotal,
                formatBytes(progress.uploadedBytes),
            )

        com.michalkulik.mkbackup.backup.BackupProgress.Phase.FINALISING ->
            stringResource(R.string.notification_progress_finalising)
    }
}
