package org.freegram.app.relay

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.freegram.app.store.RoomStore

/** Retries temporary relay failures when the phone is online. WorkManager keeps it across process death and reboot. */
class RelayRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = RoomStore.shared(applicationContext)
        store.initialize()
        val remaining = retryPendingDeliveries(store, RelayClient()::publish)
        // Stop after MAX_RUNS; states stay visible and the user can still retry manually.
        return if (remaining && runAttemptCount + 1 < MAX_RUNS) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_RUNS = 10
        private const val NAME = "relay-retry"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<RelayRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
