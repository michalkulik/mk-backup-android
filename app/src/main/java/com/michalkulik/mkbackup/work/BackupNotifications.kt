package com.michalkulik.mkbackup.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.michalkulik.mkbackup.R
import com.michalkulik.mkbackup.backup.BackupProgress
import com.michalkulik.mkbackup.core.BackupStore
import com.michalkulik.mkbackup.core.RunRecord
import com.michalkulik.mkbackup.core.RunStatus
import com.michalkulik.mkbackup.core.formatBytes
import com.michalkulik.mkbackup.ui.MainActivity

/** Notification plumbing for a running backup and for its result. */
object BackupNotifications {

    const val CHANNEL_PROGRESS = "mkbackup_progress"
    const val CHANNEL_RESULT = "mkbackup_result"

    private const val PROGRESS_NOTIFICATION_ID = 0x1b0a0001
    private const val RESULT_NOTIFICATION_ID = 0x1b0a0002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.notification_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_progress_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.notification_channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_result_description)
            },
        )
    }

    /** The ongoing notification shown while a run is in flight. Also used as the FGS notification. */
    fun progress(context: Context, setName: String, progress: BackupProgress): Notification {
        val (title, text) = describe(context, setName, progress)
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .apply {
                progress.percent?.let { setProgress(100, it, false) }
            }
            .build()
    }

    fun progressId(): Int = PROGRESS_NOTIFICATION_ID

    fun hideProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
    }

    /** Posted once at the end so the user learns about the outcome without opening the app. */
    fun result(context: Context, setName: String, record: RunRecord) {
        // The user can switch finish notifications off in Settings.
        if (!BackupStore.get(context).notificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(context.getString(R.string.notification_result_title, setName))
            .setContentText(resultText(context, record))
            .setStyle(NotificationCompat.BigTextStyle().bigText(resultText(context, record)))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun resultText(context: Context, record: RunRecord): String = when (record.status) {
        RunStatus.SUCCESS ->
            if (record.uploadedFiles == 0 && record.deletedFiles == 0) {
                context.getString(
                    R.string.notification_result_unchanged,
                    record.totalFiles,
                    formatBytes(record.totalBytes),
                )
            } else {
                context.getString(
                    R.string.notification_result_success,
                    record.uploadedFiles,
                    formatBytes(record.uploadedBytes),
                    record.addedFiles,
                    record.modifiedFiles,
                    record.deletedFiles,
                    record.version,
                )
            }

        RunStatus.FAILED -> context.getString(R.string.notification_result_failed, record.message)
        RunStatus.CANCELLED -> context.getString(R.string.notification_result_cancelled)
        RunStatus.SKIPPED -> context.getString(R.string.notification_result_skipped, record.message)
    }

    private fun describe(
        context: Context,
        setName: String,
        progress: BackupProgress,
    ): Pair<String, String> {
        val title = context.getString(R.string.notification_progress_title, setName)
        val text = when (progress.phase) {
            BackupProgress.Phase.SCANNING ->
                context.getString(R.string.notification_progress_scanning, progress.scanned)

            BackupProgress.Phase.HASHING ->
                context.getString(R.string.notification_progress_hashing, progress.hashed, progress.hashedTotal)

            BackupProgress.Phase.UPLOADING ->
                context.getString(
                    R.string.notification_progress_uploading,
                    progress.uploaded,
                    progress.uploadTotal,
                    formatBytes(progress.uploadedBytes),
                )

            BackupProgress.Phase.FINALISING -> context.getString(R.string.notification_progress_finalising)
        }
        return title to text
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
