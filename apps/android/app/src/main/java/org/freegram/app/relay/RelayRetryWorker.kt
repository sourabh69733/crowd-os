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
        val photosRemaining = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.freegram.app.media.OnlinePhotos(applicationContext, store, org.freegram.app.media.MediaStore.shared(applicationContext), org.freegram.app.identity.ProtectedIdentity(applicationContext)).sync()
        }
        val videosRemaining = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            org.freegram.app.media.OnlineVideos.forApp(applicationContext, store).sync()
        }
        val remaining = retryPendingDeliveries(store, RelayClient()::publish) || photosRemaining || videosRemaining
        // Stop after MAX_RUNS; states stay visible and the user can still retry manually.
        return if (remaining && runAttemptCount + 1 < MAX_RUNS) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_RUNS = 16
        private const val NAME = "relay-retry"

        fun schedule(context: Context) { schedule(context, ExistingWorkPolicy.APPEND_OR_REPLACE) }

        fun schedulePhotos(context: Context) { schedule(context, ExistingWorkPolicy.KEEP) }

        private fun schedule(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<RelayRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
