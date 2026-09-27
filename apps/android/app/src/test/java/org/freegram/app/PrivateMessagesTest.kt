package org.freegram.app

import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.freegram.app.moderation.ModerationMessage
import org.freegram.app.moderation.PrivateMessages
import org.freegram.app.protocol.Nip01Protocol
import org.junit.Assert.*
import org.junit.Test

class PrivateMessagesTest {
    private fun hex(v: String) = v.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun key(k: Int) = ByteArray(32).also { it[31] = k.toByte() }
    private fun pub(k: Int) = Nip01Protocol.signBulletin(key(k), "x", 0).pubkey
    private val now = 1_700_000_000L
    private val post = "ab".repeat(32)

    // From the official NIP-44 test vectors (nip44.vectors.json, "valid.get_conversation_key" and "encrypt_decrypt").
    @Test fun officialNip44Vectors() {
        val nip44 = Nip44v2()
        assertEquals("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
            nip44.getConversationKey(key(1), hex(pub(2))).joinToString("") { "%02x".format(it) })
        val payload = nip44.encryptWithNonce("a", hex("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"), hex("00".repeat(31) + "01")).encodePayload()
        assertEquals("AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb", payload)
        assertEquals("a", nip44.decrypt(payload, key(2), hex(pub(1))))
    }

    @Test fun maintainerReadsReportAndNobodyElseCan() {
        val report = ModerationMessage(ModerationMessage.Type.REPORT, pub(3), now, post, "Spam", "Same link posted 40 times")
        val wrap = PrivateMessages.wrap(key(3), pub(20), report, now)

        assertEquals(PrivateMessages.WRAP_KIND, wrap.kind)
        assertNotEquals(pub(3), wrap.pubkey) // outer signer is a one-time key
        assertTrue(wrap.createdAt in (now - 2 * 24 * 3600)..now)
        assertFalse(wrap.content.contains("Spam") || wrap.content.contains(post))
        assertEquals(listOf("p", pub(20)), wrap.tags.single().toList())

        assertEquals(report, PrivateMessages.unwrap(key(20), wrap))
        assertNull(PrivateMessages.unwrap(key(21), wrap))
    }

    @Test fun appealRoundTripsWithoutPost() {
        val appeal = ModerationMessage(ModerationMessage.Type.APPEAL, pub(4), now, null, "appeal", "That post was a real warning")
        assertEquals(appeal, PrivateMessages.unwrap(key(20), PrivateMessages.wrap(key(4), pub(20), appeal, now)))
    }

    @Test fun tamperedOrForgedWrapsAreRejected() {
        val wrap = PrivateMessages.wrap(key(3), pub(20), ModerationMessage(ModerationMessage.Type.REPORT, pub(3), now, post, "Spam", ""), now)
        assertNull(PrivateMessages.unwrap(key(20), wrap.copy(content = wrap.content.dropLast(4) + "AAAA")))
        assertNull(PrivateMessages.unwrap(key(20), wrap.copy(sig = "00".repeat(64))))
        assertNull(PrivateMessages.unwrap(key(20), wrap.copy(kind = 1)))
    }

    @Test fun sealSignerMustMatchClaimedSender() {
        // Key 5 signs a seal around a rumor that claims to come from key 3.
        val nip44 = Nip44v2()
        val tags = arrayOf(arrayOf("p", pub(20)), arrayOf("freegram", "report", "Spam"))
        val content = "Report: Spam"
        val rumor = buildJsonObject {
            put("id", JsonPrimitive(Nip01Protocol.id(pub(3), now, 14, tags, content))); put("pubkey", JsonPrimitive(pub(3)))
            put("created_at", JsonPrimitive(now)); put("kind", JsonPrimitive(14))
            put("tags", JsonArray(tags.map { t -> JsonArray(t.map(::JsonPrimitive)) })); put("content", JsonPrimitive(content))
        }.toString()
        val seal = Nip01Protocol.signEvent(key(5), 13, nip44.encrypt(rumor, key(5), hex(pub(20))).encodePayload(), now, emptyArray())
        val wrap = Nip01Protocol.signEvent(key(6), 1059, nip44.encrypt(Nip01Protocol.toJson(seal), key(6), hex(pub(20))).encodePayload(), now, arrayOf(arrayOf("p", pub(20))))
        assertNull(PrivateMessages.unwrap(key(20), wrap))
    }
}
