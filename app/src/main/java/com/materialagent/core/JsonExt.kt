package com.materialagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Defensive readers for the gateway's JSON.
 *
 * The protocol is loose by design: the same field arrives as a string in one
 * event and an object in the next (a tool `result` is an object for `terminal`
 * and a bare string for others), and servers differ across versions. Every
 * reader here returns null rather than throwing, so one odd payload can never
 * take down a whole transcript.
 */

fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

fun JsonElement?.strOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

fun JsonElement?.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull

fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

fun JsonObject?.str(key: String): String? = this?.get(key).strOrNull()

fun JsonObject?.int(key: String): Int? = this?.get(key).intOrNull()

fun JsonObject?.long(key: String): Long? = this?.get(key).longOrNull()

fun JsonObject?.double(key: String): Double? = this?.get(key).doubleOrNull()

fun JsonObject?.bool(key: String): Boolean? = this?.get(key).boolOrNull()

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key).objOrNull()

fun JsonObject?.arr(key: String): JsonArray? = this?.get(key).arrOrNull()

/** First non-null, non-blank string among [keys] — the gateway renames fields across versions. */
fun JsonObject?.strAny(vararg keys: String): String? =
    keys.asSequence().mapNotNull { this.str(it)?.takeIf(String::isNotBlank) }.firstOrNull()

/** Renders a tool result for display, whichever shape it arrived in. */
fun JsonElement?.renderResult(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}
