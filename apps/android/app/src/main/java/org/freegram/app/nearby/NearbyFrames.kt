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

/** Frames of the nearby exchange, version 1. Transport metadata (hops) stays outside the signed event. */
sealed interface NearbyFrame {
    data class Hello(val version: Int) : NearbyFrame
    data class Have(val ids: List<String>) : NearbyFrame
    data class Want(val ids: List<String>) : NearbyFrame
    data class Event(val hops: Int, val event: BulletinEvent) : NearbyFrame
    data class Ack(val id: String) : NearbyFrame
    data class Nack(val id: String, val reason: String) : NearbyFrame
    data object Sent : NearbyFrame
}

object NearbyFrames {
    const val VERSION = 1
    const val MAX_HAVE = 128
    const val MAX_WANT = 32
    const val MAX_FRAME_BYTES = 6 * 1024
    private const val MAX_REASON = 80

    fun encode(frame: NearbyFrame): String = when (frame) {
        is NearbyFrame.Hello -> buildJsonArray { add(JsonPrimitive("HELLO")); add(JsonPrimitive(frame.version)); add(JsonObject(emptyMap())) }
        is NearbyFrame.Have -> buildJsonArray { add(JsonPrimitive("HAVE")); add(JsonArray(frame.ids.map(::JsonPrimitive))) }
        is NearbyFrame.Want -> buildJsonArray { add(JsonPrimitive("WANT")); add(JsonArray(frame.ids.map(::JsonPrimitive))) }
        is NearbyFrame.Event -> buildJsonArray {
            add(JsonPrimitive("EVENT")); add(JsonPrimitive(frame.hops)); add(Json.parseToJsonElement(Nip01Protocol.toJson(frame.event)))
        }
        is NearbyFrame.Ack -> buildJsonArray { add(JsonPrimitive("ACK")); add(JsonPrimitive(frame.id)) }
        is NearbyFrame.Nack -> buildJsonArray { add(JsonPrimitive("NACK")); add(JsonPrimitive(frame.id)); add(JsonPrimitive(frame.reason.take(MAX_REASON))) }
        NearbyFrame.Sent -> buildJsonArray { add(JsonPrimitive("SENT")) }
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) }

    /** Parses and bounds one frame. Signatures are checked later, before storage. */
    fun parse(text: String): NearbyFrame {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "Frame too large" }
        val items = try { Json.parseToJsonElement(text).jsonArray } catch (failure: Exception) {
            throw IllegalArgumentException("Malformed frame", failure)
        }
        require(items.isNotEmpty()) { "Empty frame" }
        return try {
            when (items[0].jsonPrimitive.content) {
                "HELLO" -> NearbyFrame.Hello(items[1].jsonPrimitive.int)
                "HAVE" -> NearbyFrame.Have(ids(items[1], MAX_HAVE))
                "WANT" -> NearbyFrame.Want(ids(items[1], MAX_WANT))
                "EVENT" -> {
                    require(items.size == 3)
                    NearbyFrame.Event(items[1].jsonPrimitive.int, Nip01Protocol.fromJson(items[2].toString()))
                }
                "ACK" -> NearbyFrame.Ack(id(items[1].jsonPrimitive.content))
                "NACK" -> NearbyFrame.Nack(id(items[1].jsonPrimitive.content), items[2].jsonPrimitive.content.take(MAX_REASON))
                "SENT" -> NearbyFrame.Sent
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
