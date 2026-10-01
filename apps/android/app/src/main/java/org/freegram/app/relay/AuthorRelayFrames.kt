package org.freegram.app.relay

import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.json.JSONArray
import org.json.JSONObject

sealed interface AuthorFrame {
    data class Verified(val event: BulletinEvent) : AuthorFrame
    data class Closed(val reason: String) : AuthorFrame
    data object End : AuthorFrame
    data object Ignore : AuthorFrame
}

object AuthorRelayFrames {
    const val LIMIT = 50

    fun request(subscription: String, authors: List<String>, since: Long? = null, until: Long? = null): String {
        require(subscription.isNotEmpty() && subscription.length <= 64)
        require(authors.isNotEmpty() && authors.size <= 20 && authors.distinct().size == authors.size)
        require(authors.all { key -> key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } })
        return JSONArray()
            .put("REQ")
            .put(subscription)
            .put(JSONObject()
                .put("authors", JSONArray(authors))
                .put("kinds", JSONArray().put(1))
                .put("limit", LIMIT)
                .apply { since?.let { put("since", it) }; until?.let { put("until", it) } })
            .toString()
    }

    fun parse(frame: String, subscription: String, authors: Set<String>, nowSeconds: Long): AuthorFrame {
        if (frame.toByteArray(Charsets.UTF_8).size > 8192) return AuthorFrame.Ignore
        return try {
            val message = JSONArray(frame)
            if (message.optString(1) != subscription) return AuthorFrame.Ignore
            when (message.optString(0)) {
                "EOSE" -> AuthorFrame.End
                "CLOSED" -> AuthorFrame.Closed(message.optString(2).take(120))
                "EVENT" -> {
                    val wire = message.optJSONObject(2)?.toString() ?: return AuthorFrame.Ignore
                    val event = Nip01Protocol.fromJson(wire)
                    if (event.pubkey in authors && event.createdAt in 0..(nowSeconds + 600) && Nip01Protocol.verifyBulletin(event)) {
                        AuthorFrame.Verified(event)
                    } else AuthorFrame.Ignore
                }
                else -> AuthorFrame.Ignore
            }
        } catch (_: Exception) {
            AuthorFrame.Ignore
        }
    }
}
