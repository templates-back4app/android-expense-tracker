package com.back4app.expensetracker.sync

/**
 * The offline-sync rules as pure functions (no Android, no Room, no Parse), so they can be unit tested on the JVM.
 *
 *  - ops are drained in creation order; a failure blocks only the ops of the same entryKey, other keys continue
 *  - retries reuse the opId: the server's unique index on AppliedOp.opId absorbs duplicates
 *  - a queued delete supersedes queued updates of the same entry (creates are kept so the server reaches the tombstone)
 *  - a server tombstone wins over anything local: fields are frozen, pending ops for that key are dropped
 *  - "last" means server arrival order: whatever the server returns is the truth for that version
 */
object SyncPlanner {

    /** Stable order: oldest first. Two ops of the same entryKey keep their relative order. */
    fun order(ops: List<PendingOp>): List<PendingOp> = ops.sortedBy { it.createdAt }

    /**
     * The next op that may be sent: the oldest one whose entryKey has not failed in this run.
     * Ops of a blocked key are skipped so that one broken entry never stalls the others.
     */
    fun nextSendable(ops: List<PendingOp>, blockedKeys: Set<String>): PendingOp? =
        order(ops).firstOrNull { it.entryKey !in blockedKeys }

    /** Retry keeps the opId (idempotency key); only the attempt counter moves. */
    fun retry(op: PendingOp): PendingOp = op.copy(attempts = op.attempts + 1)

    /**
     * Queue a new op, applying tombstone precedence:
     *  - DELETE drops every pending UPDATE of the same key (they could only ever lose to the tombstone)
     *  - UPDATE after a pending DELETE of the same key is discarded
     *  - a second DELETE of the same key is discarded
     */
    fun enqueue(pending: List<PendingOp>, newOp: PendingOp): List<PendingOp> {
        val sameKey = pending.filter { it.entryKey == newOp.entryKey }
        val hasDelete = sameKey.any { it.op == OpType.DELETE }
        return when (newOp.op) {
            OpType.DELETE -> if (hasDelete) pending else pending.filterNot { it.entryKey == newOp.entryKey && it.op == OpType.UPDATE } + newOp
            OpType.UPDATE -> if (hasDelete) pending else pending + newOp
            OpType.CREATE -> pending + newOp
        }
    }

    /**
     * The server answered for an op of [entryKey] and the entry is now at [serverVersion]. Every op of the same key
     * that is still queued was produced on this device on top of that op's local result, so its base is that
     * version — not the one the device had seen before the chain started (0 for an entry created offline).
     * Without this, an offline create+edit or edit+edit chain is reported as a "stale baseVersion" conflict
     * although nobody else wrote (observed on the emulator run of 2026-10-23). A genuine concurrent write from
     * another device still shows: it bumps the server version *after* this rebase, and the next op's base is stale.
     */
    fun rebase(pending: List<PendingOp>, entryKey: String, serverVersion: Int): List<PendingOp> =
        pending.map { if (it.entryKey == entryKey && it.baseVersion != null) it.copy(baseVersion = serverVersion) else it }

    /** After the server reported a tombstone for `entryKey`, nothing queued for that key can still matter. */
    fun dropSupersededByTombstone(pending: List<PendingOp>, entryKey: String): List<PendingOp> =
        pending.filterNot { it.entryKey == entryKey }

    /**
     * Merge a server entry into the local row.
     *  - tombstone: always wins (fields frozen, dirty cleared)
     *  - unknown locally: insert as clean
     *  - older than what we have: ignore (idempotent re-application of a pull page)
     *  - more ops still queued for this key: keep the local field values (they are about to be sent), adopt the version
     *  - otherwise: the server fields are the truth
     */
    fun apply(local: LocalEntry?, server: ServerEntry, hasPendingOps: Boolean): LocalEntry {
        val fromServer = LocalEntry(
            entryKey = server.entryKey,
            amountCents = server.amountCents,
            category = server.category,
            date = server.date,
            note = server.note,
            version = server.version,
            deletedAt = server.deletedAt,
            dirty = false,
        )
        if (server.deletedAt != null) return fromServer
        if (local == null) return fromServer
        if (local.deletedAt != null) return local // local tombstone, server has not caught up yet: the delete op will win
        if (server.version < local.version) return local
        if (hasPendingOps) return local.copy(version = maxOf(local.version, server.version), dirty = true)
        return fromServer
    }

    /** Translate a syncOp response into what the worker must do with the op row and the conflicts table. */
    fun outcome(op: PendingOp, response: SyncResponse): OpOutcome.Done {
        val conflict = when {
            response.conflict != null -> ConflictRecord(
                entryKey = op.entryKey, opId = op.opId,
                reason = response.conflict.reason,
                baseVersion = response.conflict.baseVersion ?: op.baseVersion,
                serverVersion = response.conflict.serverVersion ?: response.entry?.version,
                fields = op.fields,
            )
            !response.applied && response.reason != null && response.reason != "duplicate opId" && response.reason != "entry exists" ->
                ConflictRecord(op.entryKey, op.opId, response.reason, op.baseVersion, response.entry?.version, op.fields)
            else -> null
        }
        return OpOutcome.Done(response.entry, conflict)
    }

    /**
     * Classify a failed call. Parse error codes that mean "the server will never accept this op" drop it;
     * everything else (no network, timeouts, 5xx, rate limits) is retried with the same opId.
     */
    fun failure(op: PendingOp, parseErrorCode: Int?, message: String): OpOutcome = when (parseErrorCode) {
        101, 141, 142, 119 -> OpOutcome.Drop(
            ConflictRecord(op.entryKey, op.opId, "rejected: $message", op.baseVersion, null, op.fields), message,
        )
        else -> OpOutcome.Retry(retry(op), message)
    }

    /** Pull cursor rule: the newest updatedAt seen, never moving backwards. */
    fun advanceCursor(current: Long?, page: List<ServerEntry>): Long? {
        val newest = page.mapNotNull { it.updatedAt }.maxOrNull() ?: return current
        return if (current == null) newest else maxOf(current, newest)
    }

    /** Tombstones are purged on the server after [TOMBSTONE_RETENTION_MS]; a cursor older than that needs a full pull. */
    fun needsFullPull(cursor: Long?, now: Long): Boolean = cursor == null || now - cursor > TOMBSTONE_RETENTION_MS

    const val TOMBSTONE_RETENTION_DAYS = 90L
    const val TOMBSTONE_RETENTION_MS = TOMBSTONE_RETENTION_DAYS * 24 * 60 * 60 * 1000
}
