package com.back4app.expensetracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE deletedAt IS NULL AND date LIKE :month || '-%' ORDER BY date DESC, entryKey")
    fun observeMonth(month: String): Flow<List<EntryEntity>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM entries WHERE deletedAt IS NULL AND date LIKE :month || '-%'")
    fun observeMonthTotal(month: String): Flow<Long>

    @Query(
        "SELECT category, SUM(amountCents) AS totalCents FROM entries WHERE deletedAt IS NULL AND date LIKE :month || '-%' " +
            "GROUP BY category ORDER BY totalCents DESC, category"
    )
    fun observeCategoryTotals(month: String): Flow<List<CategoryTotal>>

    @Query("SELECT * FROM entries WHERE entryKey = :entryKey")
    suspend fun get(entryKey: String): EntryEntity?

    @Query("SELECT * FROM entries WHERE entryKey = :entryKey")
    fun observe(entryKey: String): Flow<EntryEntity?>

    @Upsert
    suspend fun upsert(entry: EntryEntity)

    @Query("SELECT entryKey FROM entries WHERE dirty = 0")
    suspend fun cleanKeys(): List<String>

    @Query("DELETE FROM entries WHERE entryKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("SELECT COUNT(*) FROM entries WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}

@Dao
interface OpDao {
    @Query("SELECT * FROM ops ORDER BY createdAt, rowid")
    suspend fun allOrdered(): List<OpEntity>

    @Query("SELECT * FROM ops WHERE entryKey = :entryKey ORDER BY createdAt, rowid")
    suspend fun forKey(entryKey: String): List<OpEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(op: OpEntity)

    @Query("DELETE FROM ops WHERE opId = :opId")
    suspend fun delete(opId: String)

    @Query("DELETE FROM ops WHERE opId IN (:opIds)")
    suspend fun deleteByIds(opIds: List<String>)

    @Query("DELETE FROM ops WHERE entryKey = :entryKey")
    suspend fun deleteForKey(entryKey: String)

    @Query("UPDATE ops SET attempts = :attempts WHERE opId = :opId")
    suspend fun setAttempts(opId: String, attempts: Int)

    @Query("SELECT COUNT(*) FROM ops")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ops")
    suspend fun count(): Int

    @Query("DELETE FROM ops")
    suspend fun deleteAll()
}

@Dao
interface ConflictDao {
    @Insert
    suspend fun insert(conflict: ConflictEntity)

    @Query("SELECT * FROM conflicts ORDER BY recordedAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<ConflictEntity>>

    @Query("SELECT COUNT(*) FROM conflicts")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM conflicts")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Delete
    suspend fun delete(category: CategoryEntity)
}
