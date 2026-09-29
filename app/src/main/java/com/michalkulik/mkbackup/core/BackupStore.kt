package com.michalkulik.mkbackup.core

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

    /**
     * Human readable name reported to the server, Android's configured device name when present.
     * This is what "About phone" shows, e.g. `Galaxy S23+`.
     */
    fun deviceName(): String = runCatching {
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { android.os.Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: FALLBACK_DEVICE_NAME

    /**
     * The id the server keys everything by: the folder holding this phone's backups. Defaults to
     * the device name, so `sets/Galaxy S23+/…` instead of `sets/<random uuid>/…`.
     */
    val serverDeviceId: String
        get() = ensureDeviceFolder()

    /**
     * Ids this device used before, newest first: the original random id and any folder name the
     * user replaced. Every request carries them so the server can move the data across a rename
     * instead of starting an empty folder next to the old one.
     */
    val previousDeviceIds: List<String>
        get() {
            ensureDeviceFolder()
            return prefs.getString(KEY_PREVIOUS_DEVICE_IDS, "").orEmpty()
                .split(ID_SEPARATOR)
                .filter { it.isNotEmpty() }
        }

    /** Changes the server folder. The outgoing name is remembered so the data can be moved.
     *  Returns the name as stored, so callers can show what the server will actually see. */
    fun setDeviceFolderName(raw: String): String {
        val current = ensureDeviceFolder()
        val next = sanitizeDeviceFolder(raw)
            .ifEmpty { sanitizeDeviceFolder(deviceName()) }
            .ifEmpty { current }
        if (next == current) return current
        val history = (listOf(current) + previousDeviceIds)
            .distinct()
            .take(MAX_PREVIOUS_IDS)
            .joinToString(ID_SEPARATOR.toString())
        prefs.edit()
            .putString(KEY_SERVER_DEVICE_ID, next)
            .putString(KEY_PREVIOUS_DEVICE_IDS, history)
            .apply()
        touch()
        return next
    }

    /**
     * Reads (and, on the first run of this version, creates) the folder name.
     *
     * The original random id is remembered as the first previous id — that is what tells the
     * server to move the backups written before the folder was named after the device. It is
     * read without being generated: on a fresh install there is nothing on the server to move.
     */
    private fun ensureDeviceFolder(): String {
        prefs.getString(KEY_SERVER_DEVICE_ID, null)?.let { return it }
        val folder = sanitizeDeviceFolder(deviceName()).ifEmpty { FALLBACK_DEVICE_NAME }
        val legacy = prefs.getString(KEY_LEGACY_DEVICE_ID, null)
        prefs.edit()
            .putString(KEY_SERVER_DEVICE_ID, folder)
            .putString(KEY_PREVIOUS_DEVICE_IDS, legacy.orEmpty())
            .apply()
        return folder
    }

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

    // -------------------------------------------------------------- scheduling

    /**
     * Schema version of the scheduling anchor. A run created it with an older value and its
     * periodic job was anchored to the moment it was first enqueued, so the chosen start hour
     * never took effect; [com.michalkulik.mkbackup.work.BackupScheduler.reanchorIfNeeded] recreates
     * those jobs once on the first launch after the upgrade.
     */
    fun scheduleAnchorVersion(): Int = prefs.getInt(KEY_SCHEDULE_ANCHOR_VERSION, 0)

    fun setScheduleAnchorVersion(version: Int) {
        prefs.edit().putInt(KEY_SCHEDULE_ANCHOR_VERSION, version).apply()
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
        private const val KEY_LEGACY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"
        private const val KEY_PREVIOUS_DEVICE_IDS = "previous_device_ids"
        private const val KEY_DEFAULT_URL = "default_url"
        private const val KEY_DEFAULT_TOKEN = "default_token"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_SCHEDULE_ANCHOR_VERSION = "schedule_anchor_version"
        private const val MAX_RUNS = 25

        /** Separator for the remembered folder ids; stripped from names, so it cannot clash. */
        private const val ID_SEPARATOR = '|'

        /** How many replaced folder names are still offered to the server for a move. */
        private const val MAX_PREVIOUS_IDS = 5

        private const val FALLBACK_DEVICE_NAME = "Android device"

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
