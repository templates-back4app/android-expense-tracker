package com.back4app.expensetracker.sync

import android.util.Log
import com.back4app.expensetracker.ExpenseApp
import com.back4app.expensetracker.data.EntryRepository
import com.back4app.expensetracker.data.Settings

/**
 * One sync run = push (drain the outbox in order, per entryKey) then pull (listEntries since the cursor).
 * Runs inside SyncWorker; has no Android dependencies beyond Log so the flow is easy to read in one place.
 */
class SyncEngine(
    private val repo: EntryRepository,
    private val gateway: SyncGateway,
    private val settings: Settings,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { OK, RETRY, UNAUTHENTICATED }

    suspend fun run(): Outcome {
        var outcome = Outcome.OK
        var lastError: String? = null
        try {
            repeat(MAX_PASSES) {
                val push = push()
                if (push.unauthenticated) {
                    settings.lastSyncError = "Signed out on the server (session expired)."
                    return Outcome.UNAUTHENTICATED
                }
                if (push.failedKeys.isNotEmpty()) {
                    outcome = Outcome.RETRY
                    lastError = push.lastError
                }
                pull()
                // Another write may have landed while we were busy; loop while we are still making progress.
                if (push.sent == 0 || repo.pendingCount() == 0) return@repeat
            }
        } catch (e: SyncGateway.Failure) {
            Log.w(ExpenseApp.TAG, "sync failed: ${e.message}")
            settings.lastSyncError = e.message
            return if (e.code == 209) Outcome.UNAUTHENTICATED else Outcome.RETRY
        }
        settings.lastSyncAt = now()
        settings.lastSyncError = lastError
        return outcome
    }

    private data class PushResult(val sent: Int, val failedKeys: Set<String>, val lastError: String?, val unauthenticated: Boolean = false)

    private suspend fun push(): PushResult {
        val blocked = mutableSetOf<String>()
        var remaining = repo.pendingOps()
        var sent = 0
        var lastError: String? = null
        while (true) {
            val op = SyncPlanner.nextSendable(remaining, blocked) ?: break
            remaining = remaining.filterNot { it.opId == op.opId }
            try {
                val response = gateway.syncOp(op)
                val done = SyncPlanner.outcome(op, response)
                repo.completeOp(op, done)
                sent++
                if (response.entry?.deletedAt != null) remaining = SyncPlanner.dropSupersededByTombstone(remaining, op.entryKey)
            } catch (e: SyncGateway.Failure) {
                if (e.code == 209) return PushResult(sent, blocked, e.message, unauthenticated = true)
                when (val f = SyncPlanner.failure(op, e.code, e.message ?: "unknown error")) {
                    is OpOutcome.Retry -> {
                        repo.retryOp(f.op)
                        blocked += op.entryKey
                        lastError = f.message
                        Log.w(ExpenseApp.TAG, "op ${op.opId} (${op.op.wire} ${op.entryKey}) attempt ${f.op.attempts} failed: ${f.message}")
                    }
                    is OpOutcome.Drop -> {
                        repo.dropOp(op, f.conflict)
                        sent++
                        Log.w(ExpenseApp.TAG, "op ${op.opId} dropped: ${f.message}")
                    }
                    is OpOutcome.Done -> repo.completeOp(op, f)
                }
            }
        }
        return PushResult(sent, blocked, lastError)
    }

    private suspend fun pull() {
        val full = SyncPlanner.needsFullPull(settings.pullCursor, now())
        var since: Long? = if (full) null else settings.pullCursor
        var cursor: Long? = if (full) null else settings.pullCursor
        val seen = mutableSetOf<String>()
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val page = gateway.listEntries(since)
            var newInPage = 0
            for (entry in page.entries) {
                repo.applyServerEntry(entry)
                if (seen.add(entry.entryKey)) newInPage++
            }
            cursor = SyncPlanner.advanceCursor(cursor, page.entries) ?: page.cursor ?: cursor
            if (page.cursor != null && (cursor == null || page.cursor > cursor)) cursor = page.cursor
            if (!page.more || newInPage == 0) break
            since = cursor
        }
        if (full) repo.reconcileFullPull(seen)
        settings.pullCursor = cursor
    }

    private companion object {
        const val MAX_PASSES = 3
        const val MAX_PAGES = 50
    }
}
