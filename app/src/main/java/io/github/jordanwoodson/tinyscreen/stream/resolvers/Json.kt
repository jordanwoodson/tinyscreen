package io.github.jordanwoodson.tinyscreen.stream.resolvers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
internal val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.array: JsonArray? get() = this as? JsonArray

class CloudflareBlockedException : Exception("Blocked by Cloudflare")

internal fun checkNotChallenge(body: String) {
    val head = body.trimStart().take(200)
    if (head.startsWith("<!DOCTYPE", ignoreCase = true) || head.startsWith("<html", ignoreCase = true) || body.contains("Just a moment")) {
        throw CloudflareBlockedException()
    }
}
