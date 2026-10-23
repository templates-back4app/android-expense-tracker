package com.back4app.expensetracker.sync

/** Wire names match the Cloud function contract: syncOp({ opId, entryId, baseVersion, fields, op }). */
enum class OpType(val wire: String) {
    CREATE("create"), UPDATE("update"), DELETE("delete");

    companion object {
        fun fromWire(s: String): OpType = entries.first { it.wire == s }
    }
}

/** One queued write. The opId is generated once when the user acts and is reused on every retry (idempotency key). */
data class PendingOp(
    val opId: String,
    val entryKey: String,
    val op: OpType,
    val baseVersion: Int?,
    val fields: Map<String, Any?>,
    val createdAt: Long,
    val attempts: Int = 0,
)

/** What the device knows about an entry (mirrors the Room row). */
data class LocalEntry(
    val entryKey: String,
    val amountCents: Long,
    val category: String,
    val date: String,
    val note: String,
    val version: Int,
    val deletedAt: Long?,
    val dirty: Boolean,
)

/** What the server says about an entry (syncOp.entry or one item of listEntries.entries). */
data class ServerEntry(
    val entryKey: String,
    val amountCents: Long,
    val category: String,
    val date: String,
    val note: String,
    val version: Int,
    val deletedAt: Long?,
    val updatedAt: Long?,
)

data class ServerConflict(val reason: String, val baseVersion: Int?, val serverVersion: Int?)

/** syncOp response: { applied, reason?, entry, conflict? }. */
data class SyncResponse(
    val applied: Boolean,
    val reason: String?,
    val entry: ServerEntry?,
    val conflict: ServerConflict?,
)

/** A row for the local `conflicts` table — kept as evidence, never shown as a "resolved" badge. */
data class ConflictRecord(
    val entryKey: String,
    val opId: String,
    val reason: String,
    val baseVersion: Int?,
    val serverVersion: Int?,
    val fields: Map<String, Any?>,
)

/** Result of sending one op. */
sealed interface OpOutcome {
    /** The server answered: the op is consumed whatever `applied` says (false = duplicate / tombstone / exists). */
    data class Done(val entry: ServerEntry?, val conflict: ConflictRecord?) : OpOutcome

    /** Transient failure (network, 5xx): keep the op, same opId, attempts + 1. */
    data class Retry(val op: PendingOp, val message: String) : OpOutcome

    /** The server rejected the op for good (validation, unknown entry): drop it and record why. */
    data class Drop(val conflict: ConflictRecord, val message: String) : OpOutcome
}
