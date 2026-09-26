package com.michalkulik.mkbackup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michalkulik.mkbackup.R
import com.michalkulik.mkbackup.core.BackupSet
import com.michalkulik.mkbackup.core.DeviceAccess
import com.michalkulik.mkbackup.core.normalizeServerUrl

private val StringListSaver: Saver<List<String>, ArrayList<String>> = Saver(
    save = { ArrayList(it) },
    restore = { it.toList() },
)

/** Create or edit one backup set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetEditScreen(
    existing: BackupSet?,
    isNew: Boolean,
    defaultsForNew: () -> BackupSet,
    context: Context,
    onSave: (BackupSet) -> Unit,
    onBack: () -> Unit,
) {
    val initial = remember(existing?.id) { existing ?: defaultsForNew() }

    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var serverUrl by rememberSaveable(initial.id) { mutableStateOf(initial.serverUrl) }
    var token by rememberSaveable(initial.id) { mutableStateOf(initial.token) }
    var intervalHours by rememberSaveable(initial.id) { mutableStateOf(initial.intervalHours) }
    var scheduleHour by rememberSaveable(initial.id) { mutableStateOf(initial.scheduleHour) }
    var keepVersions by rememberSaveable(initial.id) { mutableStateOf(initial.keepVersions) }
    var requireCharging by rememberSaveable(initial.id) { mutableStateOf(initial.requireCharging) }
    var requireUnmetered by rememberSaveable(initial.id) { mutableStateOf(initial.requireUnmetered) }
    var showToken by rememberSaveable(initial.id) { mutableStateOf(false) }
    var exclusions by rememberSaveable(initial.id) {
        mutableStateOf(initial.excludePatterns.joinToString(", "))
    }
    var folders by rememberSaveable(initial.id, stateSaver = StringListSaver) {
        mutableStateOf(initial.folders)
    }
    var validation by remember { mutableStateOf<Int?>(null) }
    var showBrowser by rememberSaveable(initial.id) { mutableStateOf(false) }

    // The in-app browser needs broad storage access. "All files access" cannot be granted with a
    // normal runtime prompt, so the user is sent to the system screen; the browser opens once they
    // return. On older releases the plain read permission is enough.
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (DeviceAccess.hasAllFilesAccess(context)) showBrowser = true
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showBrowser = true
    }

    fun browseStorage() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                if (DeviceAccess.hasAllFilesAccess(context)) {
                    showBrowser = true
                } else {
                    runCatching { allFilesAccessLauncher.launch(DeviceAccess.allFilesAccessIntent(context)) }
                }

            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED -> showBrowser = true

            else -> legacyStoragePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    if (showBrowser) {
        FolderBrowserDialog(
            root = Environment.getExternalStorageDirectory(),
            alreadySelected = folders,
            onDismiss = { showBrowser = false },
            onPick = { path ->
                if (path !in folders) folders = folders + path
                showBrowser = false
            },
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            // Persist the grant, otherwise the URI stops working after a reboot.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            if (uri.toString() !in folders) folders = folders + uri.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.edit_title_new else R.string.edit_title_existing,
                        ),
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
            )
        },
        bottomBar = {
            // navigationBarsPadding keeps the Save button above the system navigation bar; the
            // Scaffold does not add those insets to a custom bottom bar.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                validation?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        validation = when {
                            name.isBlank() -> R.string.validation_name_required
                            folders.isEmpty() -> R.string.validation_folders_required
                            serverUrl.isBlank() -> R.string.validation_server_required
                            else -> null
                        }
                        if (validation == null) {
                            onSave(
                                initial.copy(
                                    name = name.trim(),
                                    folders = folders,
                                    serverUrl = normalizeServerUrl(serverUrl),
                                    token = token.trim(),
                                    intervalHours = intervalHours,
                                    scheduleHour = scheduleHour,
                                    keepVersions = keepVersions,
                                    requireCharging = requireCharging,
                                    requireUnmetered = requireUnmetered,
                                    excludePatterns = exclusions
                                        .split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() },
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                SectionCard(stringResource(R.string.section_folders)) {
                    if (folders.isEmpty()) {
                        Text(
                            stringResource(R.string.folders_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = friendlyFolderName(folder),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { folders = folders - folder }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_remove),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.action_add_folder))
                        }
                        OutlinedButton(onClick = { browseStorage() }) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.action_browse_storage))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.folders_all_access_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(stringResource(R.string.section_server)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.field_server_url)) },
                        placeholder = { Text("backup.example.com:8090") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.field_server_url_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.field_token)) },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.action_toggle_token),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                SectionCard(stringResource(R.string.section_schedule)) {
                    IntervalPicker(intervalHours) { intervalHours = it }
                    Spacer(Modifier.height(12.dp))
                    HourPicker(scheduleHour) { scheduleHour = it }
                    Spacer(Modifier.height(12.dp))
                    CheckRow(
                        label = stringResource(R.string.schedule_charging),
                        description = stringResource(R.string.schedule_charging_description),
                        checked = requireCharging,
                        onCheckedChange = { requireCharging = it },
                    )
                    CheckRow(
                        label = stringResource(R.string.schedule_unmetered),
                        description = stringResource(R.string.schedule_unmetered_description),
                        checked = requireUnmetered,
                        onCheckedChange = { requireUnmetered = it },
                    )
                }
            }

            item {
                SectionCard(stringResource(R.string.section_retention)) {
                    KeepVersionsPicker(keepVersions) { keepVersions = it }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.retention_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(stringResource(R.string.section_exclusions)) {
                    OutlinedTextField(
                        value = exclusions,
                        onValueChange = { exclusions = it },
                        placeholder = { Text("*.tmp, Cache/**, .thumbnails") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.exclusions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntervalPicker(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(1, 3, 6, 12, 24, 48, 168)
    Column {
        Text(
            stringResource(R.string.field_schedule),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { open = true }) {
            Text(scheduleLabel(value))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(scheduleLabel(option)) },
                    onClick = {
                        onChange(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HourPicker(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            stringResource(R.string.field_start_hour),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { open = true }) {
            Text(formatHour(value))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { hour ->
                DropdownMenuItem(
                    text = { Text(formatHour(hour)) },
                    onClick = {
                        onChange(hour)
                        open = false
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.start_hour_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `3` -> `03:00`; locale independent on purpose. */
fun formatHour(hour: Int): String = "%02d:00".format(hour.coerceIn(0, 23))

@Composable
private fun KeepVersionsPicker(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(1, 2, 3, 5, 10, 20, 50)
    Column {
        Text(
            stringResource(R.string.field_keep_versions),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { open = true }) {
            Text(stringResource(R.string.keep_versions_value, value))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keep_versions_value, option)) },
                    onClick = {
                        onChange(option)
                        open = false
                    },
                )
            }
        }
    }
}

/** SAF URIs and absolute paths are unreadable; show the trailing folder name instead. */
fun friendlyFolderName(uri: String): String {
    if (!uri.startsWith("content://")) {
        return uri.trimEnd('/').substringAfterLast('/').ifBlank { uri }
    }
    val decoded = Uri.decode(uri)
    val marker = decoded.substringAfterLast("tree/", missingDelimiterValue = decoded)
    val tail = marker.substringAfterLast(':', missingDelimiterValue = marker)
    return tail.trim('/').ifBlank { marker }
}
