package org.freegram.app.nearby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef
import java.util.Base64

/** Frames of the nearby exchange, version 1. Transport metadata (hops) stays outside the signed event. */
sealed interface NearbyFrame {
    /** [media]: this phone can exchange photos after posts. */
    data class Hello(val version: Int, val media: Boolean = false) : NearbyFrame
    data class Have(val ids: List<String>) : NearbyFrame
    data class Want(val ids: List<String>) : NearbyFrame
    data class Event(val hops: Int, val event: BulletinEvent) : NearbyFrame
    data class Ack(val id: String) : NearbyFrame
    data class Nack(val id: String, val reason: String) : NearbyFrame
    data object Sent : NearbyFrame
    data class BlobHave(val hashes: List<String>) : NearbyFrame
    data class BlobWant(val hashes: List<String>) : NearbyFrame
    data class Blob(val sha256: String, val size: Int) : NearbyFrame
    class Chunk(val sha256: String, val index: Int, val data: ByteArray) : NearbyFrame {
        override fun equals(other: Any?) = other is Chunk && sha256 == other.sha256 && index == other.index && data.contentEquals(other.data)
        override fun hashCode() = sha256.hashCode() * 31 + index
    }
    data class BlobAck(val sha256: String) : NearbyFrame
    data class BlobNack(val sha256: String, val reason: String) : NearbyFrame
    data object BlobSent : NearbyFrame
}

object NearbyFrames {
    const val VERSION = 1
    const val MAX_HAVE = 128
    const val MAX_WANT = 32
    const val MAX_FRAME_BYTES = 6 * 1024
    const val MAX_BLOB_HAVE = 64
    const val MAX_BLOB_WANT = 8
    const val CHUNK_BYTES = 16 * 1024
    /** A CHUNK frame carries base64 data; every other frame stays within [MAX_FRAME_BYTES]. */
    const val MAX_CHUNK_FRAME_BYTES = 24 * 1024
    private const val MAX_REASON = 80

    fun encode(frame: NearbyFrame): String = when (frame) {
        is NearbyFrame.Hello -> buildJsonArray {
            add(JsonPrimitive("HELLO")); add(JsonPrimitive(frame.version))
            add(JsonObject(if (frame.media) mapOf("media" to JsonPrimitive(1)) else emptyMap()))
        }
        is NearbyFrame.Have -> buildJsonArray { add(JsonPrimitive("HAVE")); add(JsonArray(frame.ids.map(::JsonPrimitive))) }
        is NearbyFrame.Want -> buildJsonArray { add(JsonPrimitive("WANT")); add(JsonArray(frame.ids.map(::JsonPrimitive))) }
        is NearbyFrame.Event -> buildJsonArray {
            add(JsonPrimitive("EVENT")); add(JsonPrimitive(frame.hops)); add(Json.parseToJsonElement(Nip01Protocol.toJson(frame.event)))
        }
        is NearbyFrame.Ack -> buildJsonArray { add(JsonPrimitive("ACK")); add(JsonPrimitive(frame.id)) }
        is NearbyFrame.Nack -> buildJsonArray { add(JsonPrimitive("NACK")); add(JsonPrimitive(frame.id)); add(JsonPrimitive(frame.reason.take(MAX_REASON))) }
        NearbyFrame.Sent -> buildJsonArray { add(JsonPrimitive("SENT")) }
        is NearbyFrame.BlobHave -> buildJsonArray { add(JsonPrimitive("BLOBHAVE")); add(JsonArray(frame.hashes.map(::JsonPrimitive))) }
        is NearbyFrame.BlobWant -> buildJsonArray { add(JsonPrimitive("BLOBWANT")); add(JsonArray(frame.hashes.map(::JsonPrimitive))) }
        is NearbyFrame.Blob -> buildJsonArray { add(JsonPrimitive("BLOB")); add(JsonPrimitive(frame.sha256)); add(JsonPrimitive(frame.size)) }
        is NearbyFrame.Chunk -> buildJsonArray {
            add(JsonPrimitive("CHUNK")); add(JsonPrimitive(frame.sha256)); add(JsonPrimitive(frame.index))
            add(JsonPrimitive(Base64.getEncoder().encodeToString(frame.data)))
        }
        is NearbyFrame.BlobAck -> buildJsonArray { add(JsonPrimitive("BLOBACK")); add(JsonPrimitive(frame.sha256)) }
        is NearbyFrame.BlobNack -> buildJsonArray { add(JsonPrimitive("BLOBNACK")); add(JsonPrimitive(frame.sha256)); add(JsonPrimitive(frame.reason.take(MAX_REASON))) }
        NearbyFrame.BlobSent -> buildJsonArray { add(JsonPrimitive("BLOBSENT")) }
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= if (frame is NearbyFrame.Chunk) MAX_CHUNK_FRAME_BYTES else MAX_FRAME_BYTES) }

    /** Parses and bounds one frame. Signatures are checked later, before storage. */
    fun parse(text: String): NearbyFrame {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_CHUNK_FRAME_BYTES && (bytes <= MAX_FRAME_BYTES || text.startsWith("[\"CHUNK\""))) { "Frame too large" }
        val items = try { Json.parseToJsonElement(text).jsonArray } catch (failure: Exception) {
            throw IllegalArgumentException("Malformed frame", failure)
        }
        require(items.isNotEmpty()) { "Empty frame" }
        return try {
            when (items[0].jsonPrimitive.content) {
                "HELLO" -> NearbyFrame.Hello(
                    items[1].jsonPrimitive.int,
                    (items.getOrNull(2) as? JsonObject)?.get("media")?.jsonPrimitive?.content == "1",
                )
                "HAVE" -> NearbyFrame.Have(ids(items[1], MAX_HAVE))
                "WANT" -> NearbyFrame.Want(ids(items[1], MAX_WANT))
                "EVENT" -> {
                    require(items.size == 3)
                    NearbyFrame.Event(items[1].jsonPrimitive.int, Nip01Protocol.fromJson(items[2].toString()))
                }
                "ACK" -> NearbyFrame.Ack(id(items[1].jsonPrimitive.content))
                "NACK" -> NearbyFrame.Nack(id(items[1].jsonPrimitive.content), items[2].jsonPrimitive.content.take(MAX_REASON))
                "SENT" -> NearbyFrame.Sent
                "BLOBHAVE" -> NearbyFrame.BlobHave(ids(items[1], MAX_BLOB_HAVE))
                "BLOBWANT" -> NearbyFrame.BlobWant(ids(items[1], MAX_BLOB_WANT))
                "BLOB" -> NearbyFrame.Blob(id(items[1].jsonPrimitive.content), items[2].jsonPrimitive.int.also { require(it in 1..PhotoRef.MAX_BYTES) })
                "CHUNK" -> {
                    val data = Base64.getDecoder().decode(items[3].jsonPrimitive.content)
                    require(data.size in 1..CHUNK_BYTES) { "Invalid chunk" }
                    NearbyFrame.Chunk(id(items[1].jsonPrimitive.content), items[2].jsonPrimitive.int.also { require(it >= 0) }, data)
                }
                "BLOBACK" -> NearbyFrame.BlobAck(id(items[1].jsonPrimitive.content))
                "BLOBNACK" -> NearbyFrame.BlobNack(id(items[1].jsonPrimitive.content), items[2].jsonPrimitive.content.take(MAX_REASON))
                "BLOBSENT" -> NearbyFrame.BlobSent
                else -> throw IllegalArgumentException("Unknown frame")
            }
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalArgumentException("Malformed frame", failure)
        }
    }

    private fun ids(element: kotlinx.serialization.json.JsonElement, max: Int): List<String> {
        val list = element.jsonArray.map { id(it.jsonPrimitive.content) }
        require(list.size <= max && list.distinct().size == list.size) { "Invalid ID list" }
        return list
    }

    private fun id(value: String): String {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid event ID" }
        return value
    }
}
