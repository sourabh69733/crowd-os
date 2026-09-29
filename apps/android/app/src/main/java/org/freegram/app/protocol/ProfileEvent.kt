package org.freegram.app.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** NIP-01 profile metadata (kind 0). Freegram reads and writes only the `name` field. */
object ProfileEvent {
    const val KIND = 0
    const val MAX_BYTES = 8 * 1024
    const val MAX_NAME = 40

    fun sign(secret: ByteArray, name: String, createdAt: Long): BulletinEvent {
        val clean = clean(name)
        require(clean.isNotEmpty()) { "Name can't be empty" }
        val content = buildJsonObject { put("name", JsonPrimitive(clean)) }.toString()
        return Nip01Protocol.signEvent(secret, KIND, content, createdAt, emptyArray())
    }

    /** The name from a verified kind-0 event, cleaned of control characters and trimmed; null if absent. */
    fun nameOf(event: BulletinEvent): String? {
        if (event.kind != KIND || !Nip01Protocol.verifySigned(event, MAX_BYTES)) return null
        val raw = runCatching {
            val obj = Json.parseToJsonElement(event.content).jsonObject
            (obj["name"] ?: obj["display_name"])?.jsonPrimitive?.content
        }.getOrNull() ?: return null
        return clean(raw).takeIf { it.isNotEmpty() }
    }

    private fun clean(name: String) = name.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .trim().take(MAX_NAME)
}
