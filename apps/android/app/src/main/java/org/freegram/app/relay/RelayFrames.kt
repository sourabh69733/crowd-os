package org.freegram.app.relay

import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.json.JSONArray
import org.json.JSONObject

sealed interface RelayFrame {
    data class Verified(val event: BulletinEvent) : RelayFrame
    data class Closed(val reason: String) : RelayFrame
    data object End : RelayFrame
    data object Ignore : RelayFrame
}

/** Handles only the bounded NIP-01 frames needed for a single-ID bridge check. */
object RelayFrames {
    fun request(subscription: String, id: String): String {
        require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' })
        return JSONArray()
            .put("REQ")
            .put(subscription)
            .put(JSONObject().put("ids", JSONArray().put(id)).put("limit", 1))
            .toString()
    }

    fun parse(frame: String, subscription: String, requestedId: String): RelayFrame {
        if (frame.toByteArray(Charsets.UTF_8).size > 8192) return RelayFrame.Ignore
        return try {
            val message = JSONArray(frame)
            if (message.optString(1) != subscription) return RelayFrame.Ignore
            when (message.optString(0)) {
                "EOSE" -> RelayFrame.End
                "CLOSED" -> RelayFrame.Closed(message.optString(2).take(120))
                "EVENT" -> {
                    val wire = message.optJSONObject(2)?.toString() ?: return RelayFrame.Ignore
                    val event = Nip01Protocol.fromJson(wire)
                    if (event.id == requestedId && Nip01Protocol.verifyBulletin(event)) RelayFrame.Verified(event) else RelayFrame.Ignore
                }
                else -> RelayFrame.Ignore
            }
        } catch (_: Exception) {
            RelayFrame.Ignore
        }
    }
}
