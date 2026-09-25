package com.michalkulik.mkbackup.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.michalkulik.mkbackup.core.BackupSet
import com.michalkulik.mkbackup.core.BackupStore
import java.util.concurrent.TimeUnit

/** Turns the stored sets into WorkManager jobs. */
object BackupScheduler {

    fun rescheduleAll(context: Context, store: BackupStore) {
        store.sets().forEach { set ->
            if (set.enabled && set.isRunnable) schedule(context, set) else cancel(context, set.id)
        }
    }

    /** (Re)creates the periodic job of one set. */
    fun schedule(context: Context, set: BackupSet) {
        val workManager = WorkManager.getInstance(context)
        if (!set.enabled || !set.isRunnable) {
            workManager.cancelUniqueWork(periodicName(set.id))
            return
        }

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            // WorkManager refuses anything below 15 minutes; the UI never offers less than 1 hour.
            set.intervalHours.coerceAtLeast(1).toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(constraintsFor(set))
            .setInputData(workDataOf(BackupWorker.KEY_SET_ID to set.id))
            .addTag(TAG)
            .addTag(setTag(set.id))
            .build()

        workManager.enqueueUniquePeriodicWork(
            periodicName(set.id),
            // UPDATE keeps the existing schedule when only unrelated options changed.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context, setId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(periodicName(setId))
    }

    /** Fires an immediate run, replacing any run that is still queued for the same set. */
    fun runNow(context: Context, set: BackupSet) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraintsFor(set))
            .setInputData(workDataOf(BackupWorker.KEY_SET_ID to set.id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .addTag(setTag(set.id))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            runNowName(set.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelRun(context: Context, setId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(runNowName(setId))
    }

    private fun constraintsFor(set: BackupSet) = Constraints.Builder()
        .setRequiredNetworkType(
            if (set.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresCharging(set.requireCharging)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun periodicName(setId: String) = "mkbackup-periodic-$setId"

    private fun runNowName(setId: String) = "mkbackup-run-$setId"

    /** Tag that lets the UI map a `WorkInfo` back to the backup set it belongs to. */
    fun setTag(setId: String) = "mkbackup-set:$setId"

    const val TAG = "mkbackup"
}
