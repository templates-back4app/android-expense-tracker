package com.back4app.expensetracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local copy of an expense. entryKey is a client-generated UUID, so creates work offline and retry safely. */
@Entity(tableName = "entries", indices = [Index("date"), Index("deletedAt")])
data class EntryEntity(
    @PrimaryKey val entryKey: String,
    val amountCents: Long,
    val category: String,
    val date: String,          // yyyy-MM-dd
    val note: String,
    val version: Int,          // server version last seen (0 = never synced)
    val deletedAt: Long?,      // epoch ms; non-null = tombstone
    val dirty: Boolean,        // has local changes not yet confirmed by the server
)

/** Outbox. One row per user action; removed once the server has answered. */
@Entity(tableName = "ops", indices = [Index("entryKey"), Index("createdAt")])
data class OpEntity(
    @PrimaryKey val opId: String,
    val entryKey: String,
    val op: String,            // create | update | delete
    val baseVersion: Int?,
    val fieldsJson: String,
    val createdAt: Long,
    val attempts: Int,
)

/** Evidence of what the server reported as a conflict (stale baseVersion, tombstone wins, rejected op). */
@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryKey: String,
    val opId: String,
    val reason: String,
    val baseVersion: Int?,
    val serverVersion: Int?,
    val fieldsJson: String,
    val recordedAt: Long,
)

/** Categories are a local list (not synced); entries store the category name as a string. */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val name: String,
    val sortOrder: Int,
)

data class CategoryTotal(val category: String, val totalCents: Long)
