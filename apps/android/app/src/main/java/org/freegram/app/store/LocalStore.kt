package org.freegram.app.store

import android.content.Context
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.json.JSONArray

/** Small durable prototype store. A Room schema will replace this before multi-device pilots. */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("freegram_local", Context.MODE_PRIVATE)

    fun draft(): String = prefs.getString("draft", "") ?: ""

    fun saveDraft(value: String) {
        check(prefs.edit().putString("draft", value).commit())
    }

    @Synchronized fun saveEvent(event: BulletinEvent) {
        require(Nip01Protocol.verifyBulletin(event))
        val events = JSONArray(prefs.getString("events", "[]"))
        if ((0 until events.length()).any { events.getString(it) == Nip01Protocol.toJson(event) }) return
        check(events.length() < 100) { "Local outbox is full" }
        events.put(Nip01Protocol.toJson(event))
        check(prefs.edit().putString("events", events.toString()).commit())
    }

    fun latestEvent(): BulletinEvent? {
        val events = JSONArray(prefs.getString("events", "[]"))
        if (events.length() == 0) return null
        return Nip01Protocol.fromJson(events.getString(events.length() - 1)).takeIf(Nip01Protocol::verifyBulletin)
    }

    fun relayState(eventId: String, relay: String): String = prefs.getString("relay:$eventId:$relay", "Pending") ?: "Pending"

    fun setRelayState(eventId: String, relay: String, state: String) {
        check(prefs.edit().putString("relay:$eventId:$relay", state).commit())
    }

    fun relayUrl(index: Int): String = prefs.getString("relay_url_$index", if (index == 0) "wss://relay.damus.io" else "wss://nos.lol") ?: ""

    fun setRelayUrl(index: Int, url: String) {
        require(url.startsWith("wss://"))
        check(prefs.edit().putString("relay_url_$index", url).commit())
    }
}
