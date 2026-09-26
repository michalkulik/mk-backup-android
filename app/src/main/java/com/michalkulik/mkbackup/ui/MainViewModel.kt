package com.michalkulik.mkbackup.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.michalkulik.mkbackup.core.BackupManifest
import com.michalkulik.mkbackup.core.BackupSet
import com.michalkulik.mkbackup.core.BackupStore
import com.michalkulik.mkbackup.core.RemoteVersion
import com.michalkulik.mkbackup.core.RunRecord
import com.michalkulik.mkbackup.net.BackupClient
import com.michalkulik.mkbackup.work.BackupScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Result of talking to the server about one set's versions. */
sealed interface VersionsState {
    data object Loading : VersionsState
    data class Loaded(val versions: List<RemoteVersion>) : VersionsState
    data class Failed(val message: String) : VersionsState
}

/** Drives every screen: the set list, the editor, the detail view and the settings page. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = BackupStore.get(application)

    val deviceId: String = store.deviceId

    /** Backup sets, refreshed whenever anything is written to the store. */
    val sets: StateFlow<List<BackupSet>> = store.revision
        .map { store.sets() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.sets())

    /** Run history per set id. */
    val runs: StateFlow<Map<String, List<RunRecord>>> = store.revision
        .map { revision -> store.sets().associate { it.id to store.runs(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Manifests are only needed to show how much the server already holds. */
    val manifests: StateFlow<Map<String, BackupManifest>> = store.revision
        .map { store.sets().associate { it.id to store.manifest(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val workInfos: StateFlow<List<WorkInfo>> = WorkManager.getInstance(application)
        .getWorkInfosByTagFlow(BackupScheduler.TAG)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _versions = MutableStateFlow<Map<String, VersionsState>>(emptyMap())
    val versions: StateFlow<Map<String, VersionsState>> = _versions.asStateFlow()

    private val _serverCheck = MutableStateFlow<ServerCheckState>(ServerCheckState.Idle)
    val serverCheck: StateFlow<ServerCheckState> = _serverCheck.asStateFlow()

    // ------------------------------------------------------------------ edits

    fun newSet(): BackupSet = BackupSet(
        id = UUID.randomUUID().toString(),
        name = "",
        serverUrl = store.defaultServerUrl(),
        token = store.defaultToken(),
    )

    fun saveSet(set: BackupSet) {
        val previous = store.set(set.id)
        store.saveSet(set)
        if (set.enabled && set.isRunnable) {
            // UPDATE alone keeps the old initial delay, so a changed start hour (or interval /
            // conditions) would not take effect until the next natural run. Recreate the job in
            // that case; an unrelated edit keeps the existing schedule untouched.
            val scheduleChanged = previous == null ||
                previous.intervalHours != set.intervalHours ||
                previous.scheduleHour != set.scheduleHour ||
                previous.requireCharging != set.requireCharging ||
                previous.requireUnmetered != set.requireUnmetered
            if (scheduleChanged) BackupScheduler.cancel(getApplication(), set.id)
            BackupScheduler.schedule(getApplication(), set)
        } else {
            BackupScheduler.cancel(getApplication(), set.id)
        }
    }

    fun deleteSet(set: BackupSet) {
        BackupScheduler.cancel(getApplication(), set.id)
        store.deleteSet(set.id)
    }

    fun setEnabled(set: BackupSet, enabled: Boolean) {
        saveSet(set.copy(enabled = enabled))
    }

    fun clearHistory(set: BackupSet) {
        store.clearRuns(set.id)
    }

    /** Drops the local diff base; the next run compares against the server instead. */
    fun resetManifest(set: BackupSet) {
        store.clearManifest(set.id)
    }

    fun runNow(set: BackupSet) {
        BackupScheduler.runNow(getApplication(), set)
    }

    fun cancelRun(set: BackupSet) {
        BackupScheduler.cancelRun(getApplication(), set.id)
    }

    // --------------------------------------------------------------- defaults

    fun defaultServerUrl(): String = store.defaultServerUrl()

    fun defaultToken(): String = store.defaultToken()

    fun saveDefaults(url: String, token: String) = store.setDefaults(url, token)

    // ----------------------------------------------------------------- server

    fun refreshVersions(set: BackupSet) {
        _versions.value = _versions.value + (set.id to VersionsState.Loading)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BackupClient(set.serverUrl, set.token).use { client ->
                        client.versions(deviceId, set.id)
                    }
                }
            }
            _versions.value = _versions.value + (
                set.id to result.fold(
                    onSuccess = { VersionsState.Loaded(it) },
                    onFailure = { VersionsState.Failed(it.message ?: "Unknown error") },
                )
                )
        }
    }

    fun deleteVersion(set: BackupSet, version: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    BackupClient(set.serverUrl, set.token).use { client ->
                        client.deleteVersion(deviceId, set.id, version)
                    }
                }
            }
            refreshVersions(set)
        }
    }

    fun checkServer(url: String, token: String) {
        _serverCheck.value = ServerCheckState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupClient(url, token).use { it.health() } }
            }
            _serverCheck.value = result.fold(
                onSuccess = { ServerCheckState.Ok(it.status, it.version) },
                onFailure = { ServerCheckState.Failed(it.message ?: "Unknown error") },
            )
        }
    }

    fun clearServerCheck() {
        _serverCheck.value = ServerCheckState.Idle
    }
}

sealed interface ServerCheckState {
    data object Idle : ServerCheckState
    data object Checking : ServerCheckState
    data class Ok(val status: String, val version: String) : ServerCheckState
    data class Failed(val message: String) : ServerCheckState
}
