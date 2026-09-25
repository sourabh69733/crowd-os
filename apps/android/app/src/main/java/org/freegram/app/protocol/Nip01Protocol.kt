package org.freegram.app.protocol

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class BulletinEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: Array<Array<String>>,
    val content: String,
    val sig: String,
) {
    override fun equals(other: Any?): Boolean = other is BulletinEvent &&
        id == other.id && pubkey == other.pubkey && createdAt == other.createdAt &&
        kind == other.kind && tags.contentDeepEquals(other.tags) && content == other.content && sig == other.sig

    override fun hashCode(): Int = id.hashCode()
}

object Nip01Protocol {
    private const val MAX_CONTENT_BYTES = 2048
    private const val MAX_EVENT_BYTES = 4096
    private const val MAX_TAGS = 8
    private val hex = "0123456789abcdef".toCharArray()

    fun serializeForId(pubkey: String, createdAt: Long, kind: Int, tags: Array<Array<String>>, content: String): String =
        buildJsonArray {
            add(0)
            add(pubkey)
            add(createdAt)
            add(kind)
            add(buildJsonArray {
                tags.forEach { tag -> add(buildJsonArray { tag.forEach(::add) }) }
            })
            add(content)
        }.toString()

    fun id(pubkey: String, createdAt: Long, kind: Int, tags: Array<Array<String>>, content: String): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(serializeForId(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8)))

    fun signBulletin(secret: ByteArray, content: String, createdAt: Long): BulletinEvent {
        require(secret.size == 32)
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES)
        val pubkey = bytesToHex(Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33))
        val tags = emptyArray<Array<String>>()
        val id = id(pubkey, createdAt, 1, tags, content)
        val aux = ByteArray(32).also(SecureRandom()::nextBytes)
        val sig = bytesToHex(Secp256k1.signSchnorr(hexToBytes(id), secret, aux))
        return BulletinEvent(id, pubkey, createdAt, 1, tags, content, sig).also {
            require(toJson(it).toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES)
        }
    }

    fun verifyBulletin(event: BulletinEvent): Boolean = try {
        event.kind == 1 && event.tags.size <= MAX_TAGS &&
            event.content.toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES &&
            toJson(event).toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES &&
            event.id.length == 64 && event.pubkey.length == 64 && event.sig.length == 128 &&
            event.id == id(event.pubkey, event.createdAt, event.kind, event.tags, event.content) &&
            Secp256k1.verifySchnorr(hexToBytes(event.sig), hexToBytes(event.id), hexToBytes(event.pubkey))
    } catch (_: Exception) {
        false
    }

    fun toJson(event: BulletinEvent): String = buildJsonObject {
        put("id", kotlinx.serialization.json.JsonPrimitive(event.id))
        put("pubkey", kotlinx.serialization.json.JsonPrimitive(event.pubkey))
        put("created_at", kotlinx.serialization.json.JsonPrimitive(event.createdAt))
        put("kind", kotlinx.serialization.json.JsonPrimitive(event.kind))
        put("tags", buildJsonArray { event.tags.forEach { tag -> add(buildJsonArray { tag.forEach(::add) }) } })
        put("content", kotlinx.serialization.json.JsonPrimitive(event.content))
        put("sig", kotlinx.serialization.json.JsonPrimitive(event.sig))
    }.toString()

    fun fromJson(wire: String): BulletinEvent {
        require(wire.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES)
        val item = Json.parseToJsonElement(wire).jsonObject
        val tags = item.getValue("tags").jsonArray.map { row ->
            row.jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
        }.toTypedArray()
        return BulletinEvent(
            id = item.getValue("id").jsonPrimitive.content,
            pubkey = item.getValue("pubkey").jsonPrimitive.content,
            createdAt = item.getValue("created_at").jsonPrimitive.long,
            kind = item.getValue("kind").jsonPrimitive.int,
            tags = tags,
            content = item.getValue("content").jsonPrimitive.content,
            sig = item.getValue("sig").jsonPrimitive.content,
        )
    }

    private fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(hex[(byte.toInt() ushr 4) and 15])
            append(hex[byte.toInt() and 15])
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { i ->
            val hi = value[i * 2].digitToInt(16)
            val lo = value[i * 2 + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
