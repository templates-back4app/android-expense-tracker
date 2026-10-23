package com.back4app.expensetracker.sync

import java.time.Instant
import java.util.Date

/**
 * Turns the loosely typed maps the Parse SDK hands back (numbers may be Int/Long/Double, dates may be
 * java.util.Date, ISO strings or {__type: Date} maps) into the typed sync models. Pure Kotlin.
 */
object ResponseParser {
    fun syncResponse(raw: Map<*, *>): SyncResponse = SyncResponse(
        applied = raw["applied"] == true,
        reason = raw["reason"] as? String,
        entry = (raw["entry"] as? Map<*, *>)?.let(::entry),
        conflict = (raw["conflict"] as? Map<*, *>)?.let {
            ServerConflict(
                reason = it["reason"] as? String ?: "conflict",
                baseVersion = int(it["baseVersion"]),
                serverVersion = int(it["serverVersion"]),
            )
        },
    )

    fun pullPage(raw: Map<*, *>): PullPage = PullPage(
        entries = (raw["entries"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::entry) },
        cursor = epochMillis(raw["cursor"]),
        more = raw["more"] == true,
    )

    fun entry(raw: Map<*, *>): ServerEntry? {
        val key = raw["entryKey"] as? String ?: return null
        return ServerEntry(
            entryKey = key,
            amountCents = long(raw["amountCents"]) ?: 0L,
            category = raw["category"] as? String ?: "",
            date = raw["date"] as? String ?: "",
            note = raw["note"] as? String ?: "",
            version = int(raw["version"]) ?: 0,
            deletedAt = epochMillis(raw["deletedAt"]),
            updatedAt = epochMillis(raw["updatedAt"]),
        )
    }

    fun long(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    fun int(v: Any?): Int? = long(v)?.toInt()

    fun epochMillis(v: Any?): Long? = when (v) {
        null -> null
        is Date -> v.time
        is Number -> v.toLong()
        is String -> runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
        is Map<*, *> -> (v["iso"] as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        else -> null
    }
}
