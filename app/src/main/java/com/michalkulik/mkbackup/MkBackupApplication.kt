package com.michalkulik.mkbackup

import android.app.Application
import com.michalkulik.mkbackup.core.BackupStore
import com.michalkulik.mkbackup.work.BackupNotifications
import com.michalkulik.mkbackup.work.BackupScheduler

/**
 * Application entry point: creates the notification channels once and makes sure the periodic jobs
 * match the stored sets (they survive reinstalls of the APK, but a set may have been edited while
 * the app was not running).
 */
class MkBackupApplication : Application() {

    val store: BackupStore by lazy { BackupStore(this) }

    override fun onCreate() {
        super.onCreate()
        BackupNotifications.ensureChannels(this)
        BackupScheduler.rescheduleAll(this, store)
    }
}
