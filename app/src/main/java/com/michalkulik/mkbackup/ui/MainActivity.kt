package com.michalkulik.mkbackup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.michalkulik.mkbackup.core.BackupStore
import com.michalkulik.mkbackup.core.DeviceAccess
import com.michalkulik.mkbackup.ui.theme.MkBackupTheme
import com.michalkulik.mkbackup.work.BackupScheduler

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Ask once at launch, but only when the user actually wants finish notifications.
        if (BackupStore.get(this).notificationsEnabled()) {
            DeviceAccess.requestNotificationPermission(this)
        }
        // One-time fix for jobs whose periodic schedule was anchored before the "start hour" existed.
        BackupScheduler.reanchorIfNeeded(this, BackupStore.get(this))
        setContent {
            MkBackupTheme {
                MkBackupApp(context = this)
            }
        }
    }
}
