package com.back4app.expensetracker.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Encodes the `fields` of an op as JSON for the `ops.fieldsJson` column. Values are Long, String, Boolean or null. */
object FieldsCodec {
    val ALLOWED = listOf("amountCents", "category", "date", "note")

    fun encode(fields: Map<String, Any?>): String {
        val obj = JsonObject(fields.mapValues { (_, v) ->
            when (v) {
                null -> JsonNull
                is Number -> JsonPrimitive(v.toLong())
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        })
        return obj.toString()
    }

    fun decode(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj.mapValues { (_, v) ->
            val p = v as? JsonPrimitive ?: return@mapValues null
            when {
                p is JsonNull -> null
                p.isString -> p.content
                p.booleanOrNull != null -> p.booleanOrNull
                else -> p.longOrNull ?: p.content
            }
        }
    }
}
