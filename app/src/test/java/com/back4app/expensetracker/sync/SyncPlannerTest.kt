package com.back4app.expensetracker.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private fun op(id: String, key: String, type: OpType, at: Long, base: Int? = null, fields: Map<String, Any?> = emptyMap(), attempts: Int = 0) =
        PendingOp(id, key, type, base, fields, at, attempts)

    private fun local(key: String, version: Int, amount: Long = 100, deletedAt: Long? = null, dirty: Boolean = false) =
        LocalEntry(key, amount, "Food", "2026-10-01", "", version, deletedAt, dirty)

    private fun server(key: String, version: Int, amount: Long = 100, deletedAt: Long? = null, updatedAt: Long? = 1_000L) =
        ServerEntry(key, amount, "Food", "2026-10-01", "", version, deletedAt, updatedAt)

    // ---- ordering ---------------------------------------------------------------------------------------------
    @Test fun `ops are drained oldest first and keep per-entry order`() {
        val ops = listOf(
            op("c", "A", OpType.UPDATE, at = 30),
            op("a", "A", OpType.CREATE, at = 10),
            op("b", "B", OpType.CREATE, at = 20),
        )
        assertEquals(listOf("a", "b", "c"), SyncPlanner.order(ops).map { it.opId })
    }

    @Test fun `a failed entry blocks only its own later ops`() {
        val ops = listOf(
            op("a1", "A", OpType.CREATE, at = 10),
            op("a2", "A", OpType.UPDATE, at = 20),
            op("b1", "B", OpType.CREATE, at = 30),
        )
        assertEquals("a1", SyncPlanner.nextSendable(ops, emptySet())!!.opId)
        // a1 failed -> A is blocked; a2 must NOT jump the queue, B proceeds
        assertEquals("b1", SyncPlanner.nextSendable(ops.drop(0), setOf("A"))!!.opId)
        assertNull(SyncPlanner.nextSendable(ops, setOf("A", "B")))
    }

    // ---- idempotency ------------------------------------------------------------------------------------------
    @Test fun `retry keeps the same opId and bumps attempts`() {
        val original = op("same-id", "A", OpType.UPDATE, at = 1, base = 2, fields = mapOf("note" to "x"))
        val retried = SyncPlanner.retry(original)
        assertEquals("same-id", retried.opId)
        assertEquals(1, retried.attempts)
        assertEquals(original.fields, retried.fields)
        assertEquals(original.baseVersion, retried.baseVersion)
        val again = SyncPlanner.retry(retried)
        assertEquals("same-id", again.opId)
        assertEquals(2, again.attempts)
    }

    @Test fun `transient failures retry with the same key, permanent rejections drop the op`() {
        val o = op("x", "A", OpType.UPDATE, at = 1, base = 1)
        val noNetwork = SyncPlanner.failure(o, 100, "i/o failure")
        assertTrue(noNetwork is OpOutcome.Retry)
        assertEquals("x", (noNetwork as OpOutcome.Retry).op.opId)
        assertTrue(SyncPlanner.failure(o, null, "timeout") is OpOutcome.Retry)
        assertTrue(SyncPlanner.failure(o, 500, "server") is OpOutcome.Retry)

        val rejected = SyncPlanner.failure(o, 142, "op must be one of create|update|delete.")
        assertTrue(rejected is OpOutcome.Drop)
        assertEquals("x", (rejected as OpOutcome.Drop).conflict.opId)
        assertTrue(rejected.conflict.reason.startsWith("rejected:"))
        assertTrue(SyncPlanner.failure(o, 101, "Unknown entryId") is OpOutcome.Drop)
    }

    @Test fun `a duplicate opId answer is a success, not a conflict`() {
        val o = op("dup", "A", OpType.CREATE, at = 1)
        val done = SyncPlanner.outcome(o, SyncResponse(applied = false, reason = "duplicate opId", entry = server("A", 1), conflict = null))
        assertNull(done.conflict)
        assertEquals(1, done.entry!!.version)
        val exists = SyncPlanner.outcome(o, SyncResponse(applied = false, reason = "entry exists", entry = server("A", 1), conflict = null))
        assertNull(exists.conflict)
    }

    // ---- tombstone precedence ----------------------------------------------------------------------------------
    @Test fun `a queued delete drops queued updates but keeps the create`() {
        val pending = listOf(
            op("c", "A", OpType.CREATE, at = 1),
            op("u1", "A", OpType.UPDATE, at = 2),
            op("u2", "A", OpType.UPDATE, at = 3),
            op("other", "B", OpType.UPDATE, at = 4),
        )
        val after = SyncPlanner.enqueue(pending, op("d", "A", OpType.DELETE, at = 5))
        assertEquals(listOf("c", "other", "d"), after.map { it.opId })
    }

    @Test fun `an update after a queued delete is discarded and a second delete is a no-op`() {
        val pending = listOf(op("d", "A", OpType.DELETE, at = 1))
        assertEquals(pending, SyncPlanner.enqueue(pending, op("u", "A", OpType.UPDATE, at = 2)))
        assertEquals(pending, SyncPlanner.enqueue(pending, op("d2", "A", OpType.DELETE, at = 3)))
        assertEquals(2, SyncPlanner.enqueue(pending, op("c", "B", OpType.CREATE, at = 4)).size)
    }

    @Test fun `a server tombstone wins over local edits and drops everything queued for that entry`() {
        val localDirty = local("A", version = 2, amount = 999, dirty = true)
        val merged = SyncPlanner.apply(localDirty, server("A", version = 3, amount = 100, deletedAt = 5_000L), hasPendingOps = true)
        assertNotNull(merged.deletedAt)
        assertEquals(3, merged.version)
        assertEquals(100L, merged.amountCents)
        assertFalse(merged.dirty)

        val pending = listOf(op("u", "A", OpType.UPDATE, at = 1), op("b", "B", OpType.UPDATE, at = 2))
        assertEquals(listOf("b"), SyncPlanner.dropSupersededByTombstone(pending, "A").map { it.opId })
    }

    @Test fun `tombstone wins answer is recorded as a conflict and consumes the op`() {
        val o = op("u", "A", OpType.UPDATE, at = 1, base = 1, fields = mapOf("note" to "late edit"))
        val done = SyncPlanner.outcome(o, SyncResponse(applied = false, reason = "tombstone wins", entry = server("A", 2, deletedAt = 1L),
            conflict = ServerConflict("tombstone", 1, 2)))
        val conflict = done.conflict!!
        assertEquals("tombstone", conflict.reason)
        assertEquals(mapOf<String, Any?>("note" to "late edit"), conflict.fields)
        assertEquals(2, conflict.serverVersion)
    }

    @Test fun `a local tombstone is kept until the server catches up`() {
        val localDeleted = local("A", version = 2, deletedAt = 10L, dirty = true)
        val merged = SyncPlanner.apply(localDeleted, server("A", version = 2), hasPendingOps = true)
        assertSame(localDeleted, merged)
    }

    // ---- applying server responses -----------------------------------------------------------------------------
    @Test fun `server fields replace local ones when nothing else is queued`() {
        val merged = SyncPlanner.apply(local("A", version = 1, amount = 5, dirty = true), server("A", version = 2, amount = 7), hasPendingOps = false)
        assertEquals(7L, merged.amountCents)
        assertEquals(2, merged.version)
        assertFalse(merged.dirty)
    }

    @Test fun `local fields are kept while more ops are queued, but the version advances`() {
        val merged = SyncPlanner.apply(local("A", version = 1, amount = 5, dirty = true), server("A", version = 2, amount = 7), hasPendingOps = true)
        assertEquals(5L, merged.amountCents)
        assertEquals(2, merged.version)
        assertTrue(merged.dirty)
    }

    @Test fun `unknown entries from a pull are inserted clean and stale pages are ignored`() {
        val inserted = SyncPlanner.apply(null, server("N", version = 4, amount = 42), hasPendingOps = false)
        assertEquals(42L, inserted.amountCents)
        assertFalse(inserted.dirty)
        val current = local("A", version = 5, amount = 1)
        assertSame(current, SyncPlanner.apply(current, server("A", version = 3, amount = 2), hasPendingOps = false))
    }

    @Test fun `stale baseVersion is applied by the server and recorded locally as a conflict`() {
        val o = op("u", "A", OpType.UPDATE, at = 1, base = 1, fields = mapOf("amountCents" to 500L))
        val done = SyncPlanner.outcome(o, SyncResponse(applied = true, reason = null, entry = server("A", 3, amount = 500),
            conflict = ServerConflict("stale baseVersion", 1, 2)))
        val conflict = done.conflict!!
        assertEquals("stale baseVersion", conflict.reason)
        assertEquals(1, conflict.baseVersion)
        assertEquals(2, conflict.serverVersion)
        assertEquals(500L, done.entry!!.amountCents) // last arrival wins: the server kept our value
    }

    // ---- pull cursor --------------------------------------------------------------------------------------------
    @Test fun `the cursor only moves forward`() {
        assertEquals(1_000L, SyncPlanner.advanceCursor(null, listOf(server("A", 1, updatedAt = 1_000L))))
        assertEquals(1_000L, SyncPlanner.advanceCursor(1_000L, listOf(server("A", 1, updatedAt = 900L))))
        assertEquals(2_000L, SyncPlanner.advanceCursor(1_000L, listOf(server("A", 1, updatedAt = 2_000L), server("B", 1, updatedAt = 1_500L))))
        assertEquals(1_000L, SyncPlanner.advanceCursor(1_000L, emptyList()))
    }

    @Test fun `a cursor older than the tombstone retention forces a full pull`() {
        val now = 100L * 24 * 60 * 60 * 1000
        assertTrue(SyncPlanner.needsFullPull(null, now))
        assertTrue(SyncPlanner.needsFullPull(now - SyncPlanner.TOMBSTONE_RETENTION_MS - 1, now))
        assertFalse(SyncPlanner.needsFullPull(now - 1_000, now))
    }
}
