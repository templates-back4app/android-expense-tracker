package com.back4app.expensetracker.data

import androidx.room.withTransaction
import com.back4app.expensetracker.sync.ConflictRecord
import com.back4app.expensetracker.sync.FieldsCodec
import com.back4app.expensetracker.sync.LocalEntry
import com.back4app.expensetracker.sync.OpOutcome
import com.back4app.expensetracker.sync.OpType
import com.back4app.expensetracker.sync.PendingOp
import com.back4app.expensetracker.sync.ServerEntry
import com.back4app.expensetracker.sync.SyncPlanner
import com.back4app.expensetracker.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Every user write goes to Room AND to the `ops` outbox in one transaction, then a sync is requested.
 * The UI never waits for the network. The sync side (SyncEngine) calls the `completeOp` / `applyServerEntry` family.
 */
class EntryRepository(
    private val db: AppDatabase,
    private val settings: Settings,
    private val scheduler: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // ---- reads -------------------------------------------------------------------------------------------------
    fun observeMonth(month: String): Flow<List<EntryEntity>> = db.entries().observeMonth(month)
    fun observeMonthTotal(month: String): Flow<Long> = db.entries().observeMonthTotal(month)
    fun observeCategoryTotals(month: String): Flow<List<CategoryTotal>> = db.entries().observeCategoryTotals(month)
    fun observeEntry(entryKey: String): Flow<EntryEntity?> = db.entries().observe(entryKey)
    fun observePendingOps(): Flow<Int> = db.ops().observeCount()
    fun observeConflictCount(): Flow<Int> = db.conflicts().observeCount()
    fun observeConflicts(): Flow<List<ConflictEntity>> = db.conflicts().observeRecent()
    fun observeCategories(): Flow<List<CategoryEntity>> = db.categories().observeAll()
    suspend fun pendingCount(): Int = db.ops().count()

    // ---- user writes -------------------------------------------------------------------------------------------
    suspend fun create(amountCents: Long, category: String, date: String, note: String): String {
        val entryKey = UUID.randomUUID().toString()
        val ts = now()
        val fields = mapOf("amountCents" to amountCents, "category" to category, "date" to date, "note" to note)
        db.withTransaction {
            db.entries().upsert(EntryEntity(entryKey, amountCents, category, date, note, version = 0, deletedAt = null, dirty = true))
            db.ops().insert(PendingOp(newOpId(), entryKey, OpType.CREATE, null, fields, ts).toEntity())
        }
        scheduler.requestSync()
        return entryKey
    }

    suspend fun update(entryKey: String, amountCents: Long, category: String, date: String, note: String) {
        var queued = false
        db.withTransaction {
            val current = db.entries().get(entryKey) ?: return@withTransaction
            if (current.deletedAt != null) return@withTransaction
            val changed = buildMap<String, Any?> {
                if (current.amountCents != amountCents) put("amountCents", amountCents)
                if (current.category != category) put("category", category)
                if (current.date != date) put("date", date)
                if (current.note != note) put("note", note)
            }
            if (changed.isEmpty()) return@withTransaction
            val pending = db.ops().forKey(entryKey).map { it.toPending() }
            val op = PendingOp(newOpId(), entryKey, OpType.UPDATE, baseVersion = current.version, fields = changed, createdAt = now())
            val queue = SyncPlanner.enqueue(pending, op)
            if (queue.none { it.opId == op.opId }) return@withTransaction // superseded by a queued delete
            db.entries().upsert(current.copy(amountCents = amountCents, category = category, date = date, note = note, dirty = true))
            db.ops().insert(op.toEntity())
            queued = true
        }
        if (queued) scheduler.requestSync()
    }

    suspend fun delete(entryKey: String) {
        var queued = false
        db.withTransaction {
            val current = db.entries().get(entryKey) ?: return@withTransaction
            if (current.deletedAt != null) return@withTransaction
            val pending = db.ops().forKey(entryKey).map { it.toPending() }
            val op = PendingOp(newOpId(), entryKey, OpType.DELETE, baseVersion = current.version, fields = emptyMap(), createdAt = now())
            val queue = SyncPlanner.enqueue(pending, op)
            val removed = pending.filter { p -> queue.none { it.opId == p.opId } }.map { it.opId }
            if (removed.isNotEmpty()) db.ops().deleteByIds(removed)
            if (queue.any { it.opId == op.opId }) db.ops().insert(op.toEntity())
            db.entries().upsert(current.copy(deletedAt = now(), dirty = true))
            queued = true
        }
        if (queued) scheduler.requestSync()
    }

    // ---- sync side ---------------------------------------------------------------------------------------------
    suspend fun pendingOps(): List<PendingOp> = db.ops().allOrdered().map { it.toPending() }

    /** The server answered for [op]: remove it from the outbox, keep the evidence, merge the returned entry. */
    suspend fun completeOp(op: PendingOp, outcome: OpOutcome.Done) {
        db.withTransaction {
            db.ops().delete(op.opId)
            outcome.conflict?.let { db.conflicts().insert(it.toEntity()) }
            outcome.entry?.let {
                applyServerEntryInTx(it)
                db.ops().rebase(op.entryKey, it.version) // see SyncPlanner.rebase
            }
        }
    }

    suspend fun retryOp(op: PendingOp) = db.ops().setAttempts(op.opId, op.attempts)

    suspend fun dropOp(op: PendingOp, conflict: ConflictRecord) {
        db.withTransaction {
            db.ops().delete(op.opId)
            db.conflicts().insert(conflict.toEntity())
        }
    }

    suspend fun applyServerEntry(server: ServerEntry) = db.withTransaction { applyServerEntryInTx(server) }

    private suspend fun applyServerEntryInTx(server: ServerEntry) {
        val local = db.entries().get(server.entryKey)?.toLocal()
        val pendingForKey = db.ops().forKey(server.entryKey)
        if (server.deletedAt != null && pendingForKey.isNotEmpty()) db.ops().deleteForKey(server.entryKey) // tombstone wins
        val merged = SyncPlanner.apply(local, server, hasPendingOps = server.deletedAt == null && pendingForKey.isNotEmpty())
        db.entries().upsert(merged.toEntity())
    }

    /** After a full pull: clean rows the server no longer returns (purged tombstones) are removed locally. */
    suspend fun reconcileFullPull(serverKeys: Set<String>) {
        db.withTransaction {
            val missing = db.entries().cleanKeys().filterNot { it in serverKeys }
            if (missing.isNotEmpty()) db.entries().deleteByKeys(missing)
        }
    }

    /** Sign-out: the local database belongs to the signed-in user. */
    suspend fun clearAll() {
        db.withTransaction {
            db.entries().deleteAll()
            db.ops().deleteAll()
            db.conflicts().deleteAll()
        }
        settings.clearSyncState()
    }

    // ---- categories (local only) --------------------------------------------------------------------------------
    suspend fun ensureDefaultCategories() {
        if (db.categories().count() == 0) {
            db.categories().insertAll(DEFAULT_CATEGORIES.mapIndexed { i, name -> CategoryEntity(name, i) })
        }
    }

    suspend fun addCategory(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        db.categories().insertAll(listOf(CategoryEntity(clean, db.categories().maxSortOrder() + 1)))
    }

    suspend fun removeCategory(category: CategoryEntity) = db.categories().delete(category)

    private fun newOpId() = UUID.randomUUID().toString()

    companion object {
        val DEFAULT_CATEGORIES = listOf("Food", "Transport", "Housing", "Utilities", "Health", "Leisure", "Other")
    }
}

// ---- mappers between Room rows and the pure sync types ------------------------------------------------------------
fun OpEntity.toPending() = PendingOp(opId, entryKey, OpType.fromWire(op), baseVersion, FieldsCodec.decode(fieldsJson), createdAt, attempts)
fun PendingOp.toEntity() = OpEntity(opId, entryKey, op.wire, baseVersion, FieldsCodec.encode(fields), createdAt, attempts)
fun EntryEntity.toLocal() = LocalEntry(entryKey, amountCents, category, date, note, version, deletedAt, dirty)
fun LocalEntry.toEntity() = EntryEntity(entryKey, amountCents, category, date, note, version, deletedAt, dirty)
fun ConflictRecord.toEntity() = ConflictEntity(
    entryKey = entryKey, opId = opId, reason = reason, baseVersion = baseVersion, serverVersion = serverVersion,
    fieldsJson = FieldsCodec.encode(fields), recordedAt = System.currentTimeMillis(),
)
