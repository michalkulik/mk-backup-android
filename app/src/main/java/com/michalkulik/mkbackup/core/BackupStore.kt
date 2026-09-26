package com.michalkulik.mkbackup.core

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Persistence for backup sets, their manifests and the run history.
 *
 * Everything is a small JSON document inside one `SharedPreferences` file. That is plenty for a
 * personal backup tool and keeps the app dependency free; each document is written whole, so a
 * partial write can never corrupt an unrelated set.
 */
class BackupStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Bumped on every write so the Compose UI can recompose from a single observable value. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    // ------------------------------------------------------------------ sets

    fun sets(): List<BackupSet> = readList(KEY_SETS, BackupSet.serializer())

    fun set(id: String): BackupSet? = sets().firstOrNull { it.id == id }

    fun saveSet(set: BackupSet) {
        val current = sets().toMutableList()
        val index = current.indexOfFirst { it.id == set.id }
        if (index >= 0) current[index] = set else current.add(set)
        writeList(KEY_SETS, BackupSet.serializer(), current)
    }

    fun deleteSet(id: String) {
        writeList(KEY_SETS, BackupSet.serializer(), sets().filterNot { it.id == id })
        prefs.edit().remove(manifestKey(id)).remove(runsKey(id)).apply()
        touch()
    }

    // -------------------------------------------------------------- manifest

    fun manifest(setId: String): BackupManifest =
        prefs.getString(manifestKey(setId), null)
            ?.let { runCatching { json.decodeFromString(BackupManifest.serializer(), it) }.getOrNull() }
            ?: BackupManifest.empty(setId)

    fun saveManifest(manifest: BackupManifest) {
        prefs.edit()
            .putString(manifestKey(manifest.setId), json.encodeToString(BackupManifest.serializer(), manifest))
            .apply()
        touch()
    }

    fun clearManifest(setId: String) {
        prefs.edit().remove(manifestKey(setId)).apply()
        touch()
    }

    // ------------------------------------------------------------ run history

    fun runs(setId: String): List<RunRecord> = readList(runsKey(setId), RunRecord.serializer())

    fun addRun(setId: String, record: RunRecord) {
        val updated = (listOf(record) + runs(setId)).take(MAX_RUNS)
        writeList(runsKey(setId), RunRecord.serializer(), updated)
    }

    fun clearRuns(setId: String) {
        prefs.edit().remove(runsKey(setId)).apply()
        touch()
    }

    // ------------------------------------------------------- device identity

    /** Stable per-installation identifier used to namespace backups on the server. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** Human readable name reported to the server, Android's configured device name when present. */
    fun deviceName(): String = runCatching {
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { android.os.Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "Android device"

    // ------------------------------------------------------- global defaults

    fun defaultServerUrl(): String = prefs.getString(KEY_DEFAULT_URL, "").orEmpty()

    fun defaultToken(): String = prefs.getString(KEY_DEFAULT_TOKEN, "").orEmpty()

    fun setDefaults(url: String, token: String) {
        prefs.edit()
            .putString(KEY_DEFAULT_URL, url)
            .putString(KEY_DEFAULT_TOKEN, token)
            .apply()
        touch()
    }

    // ----------------------------------------------------------- notifications

    /** Whether a notification is posted after a run finishes. Enabled by default. */
    fun notificationsEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS, true)

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        touch()
    }

    // ----------------------------------------------------------------- helpers

    private fun touch() {
        _revision.value = _revision.value + 1
    }

    private fun <T> readList(key: String, serializer: KSerializer<T>): List<T> =
        prefs.getString(key, null)
            ?.let { stored -> runCatching { json.decodeFromString(ListSerializer(serializer), stored) }.getOrNull() }
            .orEmpty()

    private fun <T> writeList(key: String, serializer: KSerializer<T>, value: List<T>) {
        prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), value)).apply()
        touch()
    }

    private fun manifestKey(setId: String) = "manifest_$setId"

    private fun runsKey(setId: String) = "runs_$setId"

    companion object {
        private const val PREFS = "mkbackup"
        private const val KEY_SETS = "sets"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEFAULT_URL = "default_url"
        private const val KEY_DEFAULT_TOKEN = "default_token"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val MAX_RUNS = 25

        @Volatile
        private var instance: BackupStore? = null

        /**
         * Process wide instance. The worker and the UI must share one object, otherwise the
         * [revision] signal that tells the UI to refresh would never fire after a background run.
         */
        fun get(context: Context): BackupStore =
            instance ?: synchronized(this) {
                instance ?: BackupStore(context.applicationContext).also { instance = it }
            }
    }
}
