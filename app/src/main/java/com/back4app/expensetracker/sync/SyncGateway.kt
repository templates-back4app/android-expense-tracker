package com.back4app.expensetracker.sync

/** The two Cloud functions the client needs. Implemented by ParseSyncGateway; fakes implement it in tests. */
interface SyncGateway {
    suspend fun syncOp(op: PendingOp): SyncResponse
    suspend fun listEntries(since: Long?): PullPage

    /** A failed call; [code] is the Parse error code when the server answered with one (100 = no connection). */
    class Failure(val code: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)
}

data class PullPage(val entries: List<ServerEntry>, val cursor: Long?, val more: Boolean)
