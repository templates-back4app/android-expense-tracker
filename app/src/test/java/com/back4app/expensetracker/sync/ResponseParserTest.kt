package com.back4app.expensetracker.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ResponseParserTest {
    @Test fun `parses a syncOp response with a conflict`() {
        val raw = mapOf(
            "applied" to true,
            "entry" to mapOf("entryKey" to "k1", "amountCents" to 1234, "category" to "Food", "date" to "2026-10-23", "note" to "",
                "version" to 3, "deletedAt" to null, "updatedAt" to "2026-10-23T10:00:00.000Z"),
            "conflict" to mapOf("reason" to "stale baseVersion", "baseVersion" to 1, "serverVersion" to 2),
        )
        val r = ResponseParser.syncResponse(raw)
        val entry = r.entry!!
        val conflict = r.conflict!!
        assertTrue(r.applied)
        assertNull(r.reason)
        assertEquals(3, entry.version)
        assertEquals(1234L, entry.amountCents)
        assertEquals(1_792_749_600_000L, entry.updatedAt)
        assertEquals("stale baseVersion", conflict.reason)
        assertEquals(1, conflict.baseVersion)
        assertEquals(2, conflict.serverVersion)
    }

    @Test fun `accepts dates as Date objects, ISO strings or Parse encoded maps`() {
        val iso = "2026-01-02T03:04:05.006Z"
        val millis = 1_767_323_045_006L
        assertEquals(millis, ResponseParser.epochMillis(iso))
        assertEquals(millis, ResponseParser.epochMillis(Date(millis)))
        assertEquals(millis, ResponseParser.epochMillis(mapOf("__type" to "Date", "iso" to iso)))
        assertEquals(millis, ResponseParser.epochMillis(millis))
        assertNull(ResponseParser.epochMillis(null))
        assertNull(ResponseParser.epochMillis("not a date"))
    }

    @Test fun `parses a pull page and tolerates garbage items`() {
        val raw = mapOf(
            "entries" to listOf(
                mapOf("entryKey" to "a", "amountCents" to 1.0, "version" to 1L, "deletedAt" to "2026-10-01T00:00:00Z"),
                "junk", mapOf("noKey" to true),
            ),
            "cursor" to "2026-10-23T10:00:00Z",
            "more" to false,
        )
        val page = ResponseParser.pullPage(raw)
        assertEquals(1, page.entries.size)
        assertEquals(1L, page.entries[0].amountCents)
        assertNotNull(page.entries[0].deletedAt)
        assertFalse(page.more)
        assertEquals(1_792_749_600_000L, page.cursor)
    }
}
