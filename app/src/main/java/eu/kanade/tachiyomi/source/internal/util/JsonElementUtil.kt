package eu.kanade.tachiyomi.source.internal.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

internal val JsonElement.jsonObjectOrNull: JsonObject? get() = this as? JsonObject
internal val JsonElement.jsonArrayOrNull: JsonArray? get() = this as? JsonArray
internal val JsonElement.jsonPrimitiveOrNull: JsonPrimitive? get() = this as? JsonPrimitive
internal fun JsonElement.requireObject(context: String): JsonObject =
    this as? JsonObject ?: throw IOException("$context missing object")
