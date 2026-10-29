package com.dez.sports.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Tiny null-safe accessors. ESPN's feed is loosely shaped, so every read tolerates absence.

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement?.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray) ?: emptyList()
fun JsonElement?.str(key: String): String? {
    val p = (this as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.takeIf { it.isNotEmpty() && it != "null" }
}
fun JsonElement?.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
fun JsonElement?.float(key: String): Float? = str(key)?.toFloatOrNull()
fun JsonElement?.bool(key: String): Boolean = str(key)?.toBooleanStrictOrNull() ?: false
fun JsonElement?.path(vararg keys: String): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}
fun JsonElement?.strings(key: String): List<String> =
    arr(key).map { (it as? JsonPrimitive)?.content ?: "" }
val JsonElement.content: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
