package com.michalkulik.mkbackup.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context

/** The four destinations of the app. Kept as a tiny sealed hierarchy instead of a nav graph. */
sealed interface Screen {
    data object List : Screen
    data object Settings : Screen
    data class Detail(val setId: String) : Screen
    data class Edit(val setId: String?, val isNew: Boolean) : Screen
}

private val ScreenSaver: Saver<Screen, String> = Saver(
    save = { screen ->
        when (screen) {
            Screen.List -> "list"
            Screen.Settings -> "settings"
            is Screen.Detail -> "detail:${screen.setId}"
            is Screen.Edit -> "edit:${screen.setId.orEmpty()}:${screen.isNew}"
        }
    },
    restore = { value ->
        when {
            value == "settings" -> Screen.Settings
            value.startsWith("detail:") -> Screen.Detail(value.removePrefix("detail:"))
            value.startsWith("edit:") -> {
                val parts = value.removePrefix("edit:").split(":")
                Screen.Edit(
                    setId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                    isNew = parts.getOrNull(1)?.toBoolean() ?: false,
                )
            }

            else -> Screen.List
        }
    },
)

@Composable
fun MkBackupApp(context: Context, viewModel: MainViewModel = viewModel()) {
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.List)
    }

    val sets by viewModel.sets.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val manifests by viewModel.manifests.collectAsStateWithLifecycle()
    val workInfos by viewModel.workInfos.collectAsStateWithLifecycle()
    val versions by viewModel.versions.collectAsStateWithLifecycle()
    val serverCheck by viewModel.serverCheck.collectAsStateWithLifecycle()

    when (val current = screen) {
        Screen.List -> SetListScreen(
            sets = sets,
            runs = runs,
            workInfos = workInfos,
            onOpen = { screen = Screen.Detail(it.id) },
            onAdd = { screen = Screen.Edit(setId = null, isNew = true) },
            onToggle = viewModel::setEnabled,
            onRunNow = viewModel::runNow,
            onCancel = viewModel::cancelRun,
            onSettings = { screen = Screen.Settings },
        )

        Screen.Settings -> SettingsScreen(
            context = context,
            defaultUrl = viewModel.defaultServerUrl(),
            defaultToken = viewModel.defaultToken(),
            deviceId = viewModel.deviceId,
            checkState = serverCheck,
            onSave = viewModel::saveDefaults,
            onCheck = viewModel::checkServer,
            onBack = {
                viewModel.clearServerCheck()
                screen = Screen.List
            },
        )

        is Screen.Detail -> {
            val set = sets.firstOrNull { it.id == current.setId }
            if (set == null) {
                screen = Screen.List
            } else {
                SetDetailScreen(
                    set = set,
                    runs = runs[set.id].orEmpty(),
                    manifest = manifests[set.id],
                    versions = versions[set.id],
                    running = WorkState.isActive(workInfos, set.id),
                    progress = WorkState.progressOf(workInfos, set.id),
                    onBack = { screen = Screen.List },
                    onEdit = { screen = Screen.Edit(setId = set.id, isNew = false) },
                    onDelete = {
                        viewModel.deleteSet(set)
                        screen = Screen.List
                    },
                    onRunNow = { viewModel.runNow(set) },
                    onCancel = { viewModel.cancelRun(set) },
                    onRefreshVersions = { viewModel.refreshVersions(set) },
                    onDeleteVersion = { viewModel.deleteVersion(set, it) },
                    onClearHistory = { viewModel.clearHistory(set) },
                    onResetManifest = { viewModel.resetManifest(set) },
                )
            }
        }

        is Screen.Edit -> SetEditScreen(
            existing = current.setId?.let { id -> sets.firstOrNull { it.id == id } },
            isNew = current.isNew,
            defaultsForNew = { viewModel.newSet() },
            context = context,
            onSave = { set ->
                viewModel.saveSet(set)
                screen = if (current.isNew) Screen.List else Screen.Detail(set.id)
            },
            onBack = {
                screen = if (current.isNew) Screen.List else Screen.Detail(current.setId.orEmpty())
            },
        )
    }
}
