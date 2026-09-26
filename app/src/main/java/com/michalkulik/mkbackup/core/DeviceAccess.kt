package com.michalkulik.mkbackup.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Small helpers around the two system grants the app recommends: access to all files (so arbitrary
 * folders such as `Android/data` can be backed up) and exclusion from battery optimisation (so
 * WorkManager is not deferred).
 */
object DeviceAccess {

    /**
     * True when broad storage access is available: the "All files access" special permission from
     * Android 11, or the legacy read permission on older releases.
     */
    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Opens the screen where the special permission can be granted. On older releases (no
     * "all files access") this falls back to the app's details page.
     */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }

    private fun powerManager(context: Context): PowerManager? =
        context.getSystemService(PowerManager::class.java)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        powerManager(context)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Asks the system to whitelist the app. Some OEM builds do not answer the direct request, so
     * the caller should fall back to the settings list when returning the intent fails.
     */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    // ------------------------------------------------------------ notifications

    /** Notifications need a runtime permission from Android 13 (API 33). */
    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Asks for the notification permission on Android 13+; a no-op when already granted. */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationPermission(activity)) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST,
        )
    }

    private const val NOTIFICATION_PERMISSION_REQUEST = 0x1b0a
}
