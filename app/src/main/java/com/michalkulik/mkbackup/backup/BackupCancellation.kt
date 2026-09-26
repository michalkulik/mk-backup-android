package com.michalkulik.mkbackup.backup

import com.michalkulik.mkbackup.net.BackupClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared cancellation signal for one run.
 *
 * WorkManager stopping a worker only cancels coroutines; the backup engine also performs blocking
 * OkHttp calls, which would otherwise keep running (for example a connection attempt against a
 * mistyped URL) until their timeout expires. [cancel] aborts the active request as well, so the
 * Stop button takes effect immediately.
 */
class BackupCancellation {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var client: BackupClient? = null

    val isCancelled: Boolean get() = cancelled.get()

    /** Registers the client whose requests belong to this run. */
    fun attach(client: BackupClient) {
        this.client = client
        // A cancel that arrived before the client existed must still take effect.
        if (cancelled.get()) client.cancel()
    }

    fun detach() {
        client = null
    }

    fun cancel() {
        cancelled.set(true)
        client?.cancel()
    }
}
