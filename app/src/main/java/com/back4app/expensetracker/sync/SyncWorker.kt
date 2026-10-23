package com.back4app.expensetracker.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.back4app.expensetracker.ExpenseApp
import com.parse.ParseUser

/**
 * WorkManager entry point. Constraints (network) and backoff (exponential) are set by SyncScheduler.
 * Result.retry() re-runs with backoff; after MAX_RUN_ATTEMPTS the chain ends and the next write re-enqueues it.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ExpenseApp
        val c = app.container
        if (!c.backendConfigured) return Result.failure()
        if (ParseUser.getCurrentUser() == null) return Result.success()
        val engine = SyncEngine(c.repository, c.gateway, c.settings)
        return when (engine.run()) {
            SyncEngine.Outcome.OK -> Result.success()
            SyncEngine.Outcome.UNAUTHENTICATED -> Result.failure()
            SyncEngine.Outcome.RETRY -> if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RUN_ATTEMPTS = 10
    }
}
