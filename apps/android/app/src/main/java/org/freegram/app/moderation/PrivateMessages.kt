package org.freegram.app.moderation

import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol

/** A report or appeal as the maintainer reads it after decryption. [from] is the sender's key. */
data class ModerationMessage(
    val type: Type,
    val from: String,
    val createdAt: Long,
    val postId: String?,
    val reason: String,
    val note: String,
) {
    enum class Type { REPORT, APPEAL }
}

/**
 * Private reports and appeals as NIP-17 direct messages (kind 14) inside a NIP-59 gift wrap:
 * rumor (unsigned) → seal (kind 13, signed by the sender, NIP-44 encrypted) → wrap (kind 1059, signed by a
 * one-time key, NIP-44 encrypted, `p`-tagged to the maintainer). Relays see only the wrap. Encryption is
 * Quartz's NIP-44 v2; this file only assembles and checks the events.
 */
object PrivateMessages {
    const val WRAP_KIND = 1059
    private const val SEAL_KIND = 13
    private const val MESSAGE_KIND = 14
    const val MAX_WRAP_BYTES = 32 * 1024
    const val MAX_NOTE_CHARS = 1000
    /** NIP-59: randomise outer timestamps up to two days back so they do not reveal when a report was sent. */
    private const val TIME_JITTER_SECONDS = 2 * 24 * 3600L
    private val nip44 = Nip44v2()
    private val random = SecureRandom()

    fun wrap(senderSecret: ByteArray, recipient: String, message: ModerationMessage, now: Long): BulletinEvent {
        require(message.note.length <= MAX_NOTE_CHARS) { "Note is too long" }
        val sender = pubkeyOf(senderSecret)
        val tags = buildList {
            add(arrayOf("p", recipient))
            message.postId?.let { add(arrayOf("e", it)) }
            add(arrayOf("freegram", message.type.name.lowercase(), message.reason))
        }.toTypedArray()
        val content = buildString {
            append(if (message.type == ModerationMessage.Type.REPORT) "Report" else "Appeal").append(": ").append(message.reason)
            message.postId?.let { append("\nPost: ").append(it) }
            if (message.note.isNotBlank()) append("\n").append(message.note)
        }
        val rumorId = Nip01Protocol.id(sender, now, MESSAGE_KIND, tags, content)
        val rumor = buildJsonObject {
            put("id", JsonPrimitive(rumorId)); put("pubkey", JsonPrimitive(sender)); put("created_at", JsonPrimitive(now))
            put("kind", JsonPrimitive(MESSAGE_KIND)); put("tags", JsonArray(tags.map { t -> JsonArray(t.map(::JsonPrimitive)) }))
            put("content", JsonPrimitive(content))
        }.toString()
        val recipientKey = hex(recipient)
        val seal = Nip01Protocol.signEvent(
            senderSecret, SEAL_KIND, nip44.encrypt(rumor, senderSecret, recipientKey).encodePayload(), jittered(now), emptyArray(),
        )
        val oneTime = newSecret()
        try {
            return Nip01Protocol.signEvent(
                oneTime, WRAP_KIND, nip44.encrypt(Nip01Protocol.toJson(seal), oneTime, recipientKey).encodePayload(),
                jittered(now), arrayOf(arrayOf("p", recipient)),
            ).also { require(Nip01Protocol.toJson(it).length <= MAX_WRAP_BYTES) }
        } finally { oneTime.fill(0) }
    }

    /** Opens a gift wrap addressed to [recipientSecret]'s key. Returns null for anything forged, malformed or not ours. */
    fun unwrap(recipientSecret: ByteArray, wrap: BulletinEvent): ModerationMessage? = try {
        val me = pubkeyOf(recipientSecret)
        require(wrap.kind == WRAP_KIND && wrap.tags.any { it.getOrNull(0) == "p" && it.getOrNull(1) == me })
        require(Nip01Protocol.verifySigned(wrap, MAX_WRAP_BYTES))
        val seal = Nip01Protocol.fromJson(nip44.decrypt(wrap.content, recipientSecret, hex(wrap.pubkey)), MAX_WRAP_BYTES)
        require(seal.kind == SEAL_KIND && Nip01Protocol.verifySigned(seal, MAX_WRAP_BYTES))
        val rumor = Json.parseToJsonElement(nip44.decrypt(seal.content, recipientSecret, hex(seal.pubkey))).jsonObject
        val sender = rumor.getValue("pubkey").jsonPrimitive.content
        // The seal's signature is what authenticates the sender; the rumor must claim the same key.
        require(sender == seal.pubkey)
        require(rumor.getValue("kind").jsonPrimitive.int == MESSAGE_KIND)
        val tags = rumor.getValue("tags").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content }.toTypedArray() }.toTypedArray()
        val createdAt = rumor.getValue("created_at").jsonPrimitive.long
        val content = rumor.getValue("content").jsonPrimitive.content
        require(rumor.getValue("id").jsonPrimitive.content == Nip01Protocol.id(sender, createdAt, MESSAGE_KIND, tags, content))
        val marker = tags.firstOrNull { it.getOrNull(0) == "freegram" } ?: return null
        val type = when (marker.getOrNull(1)) { "report" -> ModerationMessage.Type.REPORT; "appeal" -> ModerationMessage.Type.APPEAL; else -> return null }
        val postId = tags.firstOrNull { it.getOrNull(0) == "e" }?.getOrNull(1)?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        val note = content.lines().drop(1).filterNot { it.startsWith("Post: ") }.joinToString("\n").take(MAX_NOTE_CHARS)
        ModerationMessage(type, sender, createdAt, postId, marker.getOrNull(2).orEmpty().take(80), note)
    } catch (_: Exception) {
        null
    }

    private fun jittered(now: Long) = now - (random.nextDouble() * TIME_JITTER_SECONDS).toLong()

    private fun newSecret(): ByteArray {
        val secret = ByteArray(32)
        do random.nextBytes(secret) while (runCatching { Secp256k1.pubkeyCreate(secret) }.isFailure)
        return secret
    }

    private fun pubkeyOf(secret: ByteArray) = Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33).joinToString("") { "%02x".format(it) }
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
