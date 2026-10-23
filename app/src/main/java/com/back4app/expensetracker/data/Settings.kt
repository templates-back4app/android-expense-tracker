package com.back4app.expensetracker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Small key/value state: monthly budget, pull cursor, last sync result. SharedPreferences is enough here. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("expense-tracker", Context.MODE_PRIVATE)

    var monthlyBudgetCents: Long
        get() = prefs.getLong(KEY_BUDGET, 0L)
        set(value) = prefs.edit().putLong(KEY_BUDGET, value).apply()

    /** Server updatedAt (epoch ms) of the newest entry pulled so far; null = never pulled. */
    var pullCursor: Long?
        get() = if (prefs.contains(KEY_CURSOR)) prefs.getLong(KEY_CURSOR, 0L) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_CURSOR) else putLong(KEY_CURSOR, value) }.apply()

    var lastSyncAt: Long?
        get() = if (prefs.contains(KEY_LAST_SYNC)) prefs.getLong(KEY_LAST_SYNC, 0L) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_LAST_SYNC) else putLong(KEY_LAST_SYNC, value) }.apply()

    var lastSyncError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, value) }.apply()

    /** objectId of the account whose data is in Room; a different account signing in clears the database. */
    var lastUserId: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_USER) else putString(KEY_USER, value) }.apply()

    fun clearSyncState() {
        prefs.edit().remove(KEY_CURSOR).remove(KEY_LAST_SYNC).remove(KEY_LAST_ERROR).apply()
    }

    /** Emits on every change so Compose screens can observe budget and sync status. */
    fun changes(): Flow<Long> = callbackFlow {
        trySend(System.currentTimeMillis())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(System.currentTimeMillis()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val KEY_BUDGET = "monthlyBudgetCents"
        const val KEY_CURSOR = "pullCursor"
        const val KEY_LAST_SYNC = "lastSyncAt"
        const val KEY_LAST_ERROR = "lastSyncError"
        const val KEY_USER = "lastUserId"
    }
}
