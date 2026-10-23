package com.back4app.expensetracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldsCodecTest {
    @Test fun `round trips longs strings and nulls`() {
        val fields = mapOf<String, Any?>("amountCents" to 1234L, "category" to "Food", "date" to "2026-10-23", "note" to "", "x" to null)
        val json = FieldsCodec.encode(fields)
        assertEquals(fields, FieldsCodec.decode(json))
    }

    @Test fun `ints are normalised to longs so equality works after a round trip`() {
        assertEquals(mapOf<String, Any?>("amountCents" to 5L), FieldsCodec.decode(FieldsCodec.encode(mapOf("amountCents" to 5))))
    }

    @Test fun `empty json is an empty map`() {
        assertEquals(emptyMap<String, Any?>(), FieldsCodec.decode(""))
        assertEquals(emptyMap<String, Any?>(), FieldsCodec.decode("{}"))
    }
}
