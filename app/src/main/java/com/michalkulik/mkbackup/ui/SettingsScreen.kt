package com.michalkulik.mkbackup.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.michalkulik.mkbackup.BuildConfig
import com.michalkulik.mkbackup.R
import com.michalkulik.mkbackup.core.DeviceAccess
import com.michalkulik.mkbackup.core.normalizeServerUrl

/**
 * Global defaults. A backup set copies these when it is created, so changing them here never
 * silently rewrites an existing set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    context: Context,
    defaultUrl: String,
    defaultToken: String,
    deviceId: String,
    checkState: ServerCheckState,
    onSave: (String, String) -> Unit,
    onCheck: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var url by rememberSaveable(defaultUrl) { mutableStateOf(defaultUrl) }
    var token by rememberSaveable(defaultToken) { mutableStateOf(defaultToken) }
    var saved by remember { mutableStateOf(false) }

    var allFilesAccess by remember { mutableStateOf(DeviceAccess.hasAllFilesAccess(context)) }
    var ignoringBattery by remember { mutableStateOf(DeviceAccess.isIgnoringBatteryOptimizations(context)) }
    // Re-check the special grants whenever the user comes back from the system settings screens.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allFilesAccess = DeviceAccess.hasAllFilesAccess(context)
        ignoringBattery = DeviceAccess.isIgnoringBatteryOptimizations(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_default_server),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_default_server_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it; saved = false },
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
                            onValueChange = { token = it; saved = false },
                            label = { Text(stringResource(R.string.field_token)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val normalized = normalizeServerUrl(url)
                                    url = normalized
                                    onSave(normalized, token.trim())
                                    saved = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.action_save)) }

                            OutlinedButton(
                                onClick = { onCheck(normalizeServerUrl(url), token.trim()) },
                                enabled = url.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.action_check_server)) }
                        }

                        if (saved) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.settings_saved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        when (checkState) {
                            ServerCheckState.Idle -> Unit
                            ServerCheckState.Checking -> Text(
                                stringResource(R.string.server_check_checking),
                                style = MaterialTheme.typography.bodySmall,
                            )

                            is ServerCheckState.Ok -> Text(
                                stringResource(R.string.server_check_ok, checkState.status, checkState.version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            is ServerCheckState.Failed -> Text(
                                stringResource(R.string.server_check_failed, checkState.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                PermissionCard(
                    title = stringResource(R.string.settings_all_files),
                    description = stringResource(R.string.settings_all_files_description),
                    granted = allFilesAccess,
                    grantedLabel = stringResource(R.string.permission_granted),
                    missingLabel = stringResource(R.string.permission_not_granted),
                    actionLabel = stringResource(R.string.action_grant_access),
                    onAction = {
                        runCatching { context.startActivity(DeviceAccess.allFilesAccessIntent(context)) }
                    },
                )
            }

            item {
                PermissionCard(
                    title = stringResource(R.string.settings_battery),
                    description = stringResource(R.string.settings_battery_description),
                    granted = ignoringBattery,
                    grantedLabel = stringResource(R.string.permission_granted),
                    missingLabel = stringResource(R.string.permission_not_granted),
                    actionLabel = stringResource(R.string.action_disable_battery_optimization),
                    onAction = {
                        val direct = runCatching {
                            context.startActivity(DeviceAccess.batteryOptimizationIntent(context))
                        }
                        if (direct.isFailure) {
                            runCatching {
                                context.startActivity(DeviceAccess.batteryOptimizationSettingsIntent())
                            }
                        }
                    },
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_device), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_device_id),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(deviceId, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_about_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    missingLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (granted) grantedLabel else missingLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
        }
    }
}
