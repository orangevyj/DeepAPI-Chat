package com.orangestudio.deepapichat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal builders for `JsonElement` trees.
 *
 * Deliberately hand-rolled from the stable [JsonObject]/[JsonArray]/[JsonPrimitive]
 * constructors instead of kotlinx.serialization's `buildJsonObject` DSL, whose
 * `putJsonObject`/`addJsonObject` helpers carry opt-in annotations. Request bodies
 * are built by hand because the OpenAI wire format has several "omit this field"
 * rules that a DTO with defaults cannot express.
 */
fun jsonObject(block: JsonObjectScope.() -> Unit): JsonObject = JsonObjectScope().apply(block).build()

fun jsonArray(block: JsonArrayScope.() -> Unit): JsonArray = JsonArrayScope().apply(block).build()

class JsonObjectScope internal constructor() {
    private val entries = LinkedHashMap<String, JsonElement>()

    fun put(key: String, value: String) { entries[key] = JsonPrimitive(value) }
    fun put(key: String, value: Int) { entries[key] = JsonPrimitive(value) }
    fun put(key: String, value: Long) { entries[key] = JsonPrimitive(value) }
    fun put(key: String, value: Double) { entries[key] = JsonPrimitive(value) }
    fun put(key: String, value: Boolean) { entries[key] = JsonPrimitive(value) }
    fun put(key: String, value: JsonElement) { entries[key] = value }

    fun putIfPresent(key: String, value: String?) { if (value != null) entries[key] = JsonPrimitive(value) }
    fun putIf(condition: Boolean, key: String, value: JsonElement) { if (condition) entries[key] = value }

    fun array(key: String, values: List<JsonElement>) { entries[key] = JsonArray(values) }
    fun strings(key: String, values: List<String>) { entries[key] = JsonArray(values.map { JsonPrimitive(it) }) }
    fun obj(key: String, block: JsonObjectScope.() -> Unit) { entries[key] = jsonObject(block) }
    fun arr(key: String, block: JsonArrayScope.() -> Unit) { entries[key] = jsonArray(block) }

    fun build(): JsonObject = JsonObject(entries)
}

class JsonArrayScope internal constructor() {
    private val items = ArrayList<JsonElement>()

    fun add(value: JsonElement) { items.add(value) }
    fun add(value: String) { items.add(JsonPrimitive(value)) }
    fun addObject(block: JsonObjectScope.() -> Unit) { items.add(jsonObject(block)) }

    fun build(): JsonArray = JsonArray(items)
}

/**
 * Reads a string out of a JSON value. Returns null for missing values, JSON
 * `null`, and non-primitive nodes rather than throwing.
 */
fun JsonElement?.stringValue(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

fun JsonElement?.objectValue(): JsonObject? = this as? JsonObject
