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
import java.time.Duration
import java.time.ZonedDateTime
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
            // Align the first run with the chosen hour, then repeat from there.
            .setInitialDelay(delayUntilHour(set.scheduleHour), TimeUnit.MILLISECONDS)
            .setConstraints(constraintsFor(set))
            .setInputData(workDataOf(BackupWorker.KEY_SET_ID to set.id))
            .addTag(TAG)
            .addTag(periodicTag(set.id))
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

    /**
     * Fires an immediate run, replacing any run that is still queued for the same set.
     *
     * The charging / battery-not-low conditions of the periodic job are deliberately not applied:
     * they exist so the app does not wake the phone on its own schedule, and must not block a run
     * the user just asked for. The network preference is still honoured.
     *
     * They also *cannot* be applied here: WorkManager rejects
     * `Expedited jobs only support network and storage constraints`, so passing the periodic
     * constraints to an expedited request crashes the app.
     */
    fun runNow(context: Context, set: BackupSet) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(runNowConstraints(set))
            .setInputData(workDataOf(BackupWorker.KEY_SET_ID to set.id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .addTag(runTag(set.id))
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

    /** Constraints allowed on an expedited request: network only, never battery or charging. */
    private fun runNowConstraints(set: BackupSet) = Constraints.Builder()
        .setRequiredNetworkType(
            if (set.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .build()

    private fun constraintsFor(set: BackupSet) = Constraints.Builder()
        .setRequiredNetworkType(
            if (set.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresCharging(set.requireCharging)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Milliseconds from now until the next occurrence of the preferred hour of day. */
    private fun delayUntilHour(hour: Int): Long {
        val safeHour = hour.coerceIn(0, 23)
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(safeHour, 0).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis().coerceAtLeast(0L)
    }

    private fun periodicName(setId: String) = "mkbackup-periodic-$setId"

    private fun runNowName(setId: String) = "mkbackup-run-$setId"

    /**
     * Tag on the one-off "run now" work. This is the only tag that means "a backup is happening":
     * the one-off request is ENQUEUED only while it is actually waiting to start.
     */
    fun runTag(setId: String) = "mkbackup-run-set:$setId"

    /**
     * Tag on the periodic work. It must be distinct from [runTag] because a periodic request spends
     * almost its entire life in the ENQUEUED state, waiting for its next interval - which is not the
     * same thing as a backup being in progress.
     */
    fun periodicTag(setId: String) = "mkbackup-periodic-set:$setId"

    const val TAG = "mkbackup"
}
