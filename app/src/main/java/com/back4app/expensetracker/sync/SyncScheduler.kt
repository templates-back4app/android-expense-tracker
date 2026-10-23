package com.back4app.expensetracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Enqueues SyncWorker: once per write (unique, network-constrained, exponential backoff) plus a periodic pull. */
class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Request a sync soon. [force] replaces a running one (used by "Sync now"). */
    fun requestSync(force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(SYNC_NOW, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Catches changes made on other devices even when this one has nothing to push. */
    fun ensurePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(SYNC_NOW)
        workManager.cancelUniqueWork(SYNC_PERIODIC)
    }

    /**
     * True only while the one-time sync is actually running. ENQUEUED also covers "constraints not met" (offline)
     * and backoff, which the Settings screen reports as "waiting for network / retry" from the pending count.
     */
    fun observeSyncing(): Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(SYNC_NOW).map { infos ->
        infos.any { it.state == WorkInfo.State.RUNNING }
    }

    private companion object {
        const val SYNC_NOW = "sync-now"
        const val SYNC_PERIODIC = "sync-periodic"
        const val TAG = "sync"
    }
}
