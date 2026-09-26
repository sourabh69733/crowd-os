package org.freegram.app

import fr.acinq.secp256k1.Secp256k1
import org.freegram.app.protocol.Nip01Protocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolCompatibilityTest {
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun officialBip340VectorZero() {
        val secret = hex("00".repeat(31) + "03")
        val message = ByteArray(32)
        val publicKey = hex("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9")
        val signature = hex("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0")
        assertArrayEquals(publicKey, Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33))
        assertArrayEquals(signature, Secp256k1.signSchnorr(message, secret, ByteArray(32)))
        assertTrue(Secp256k1.verifySchnorr(signature, message, publicKey))
        val modified = message.copyOf().also { it[0] = 1 }
        assertFalse(Secp256k1.verifySchnorr(signature, modified, publicKey))
    }

    @Test fun officialBip340NostrLengthVectors() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/bip340-test-vectors.csv"))
            .bufferedReader().use { it.readLines() }.drop(1)
        for (line in lines) {
            val fields = line.split(",", limit = 8)
            if (fields.size < 7 || fields[4].length != 64) continue
            val index = fields[0]
            val publicKey = hex(fields[2])
            val message = hex(fields[4])
            val signature = hex(fields[5])
            val expected = fields[6] == "TRUE"
            val verified = try { Secp256k1.verifySchnorr(signature, message, publicKey) } catch (_: Exception) { false }
            assertEquals("vector $index", expected, verified)
            if (fields[1].isNotEmpty()) {
                assertArrayEquals("vector $index signing", signature, Secp256k1.signSchnorr(message, hex(fields[1]), hex(fields[3])))
            }
        }
    }

    @Test fun portableNip01Ids() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/nip01-id-v1.json"))
        val cases = JSONObject(stream.bufferedReader().use { it.readText() }).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val tagsJson = item.getJSONArray("tags")
            val tags = Array(tagsJson.length()) { i ->
                val tag = tagsJson.getJSONArray(i)
                Array(tag.length()) { j -> tag.getString(j) }
            }
            assertEquals(item.getString("serialized"), Nip01Protocol.serializeForId(
                item.getString("pubkey"), item.getLong("created_at"), item.getInt("kind"), tags, item.getString("content")
            ))
            assertEquals(item.getString("id"), Nip01Protocol.id(
                item.getString("pubkey"), item.getLong("created_at"), item.getInt("kind"), tags, item.getString("content")
            ))
        }
    }

    @Test fun signedBulletinRejectsTamperingAndOversizeText() {
        val secret = hex("00".repeat(31) + "03")
        val event = Nip01Protocol.signBulletin(secret, "Test bulletin", 1_700_000_000)
        assertTrue(Nip01Protocol.verifyBulletin(event))
        assertEquals(event, Nip01Protocol.fromJson(Nip01Protocol.toJson(event)))
        assertFalse(Nip01Protocol.verifyBulletin(event.copy(content = "changed")))
        assertFalse(Nip01Protocol.verifyBulletin(event.copy(sig = "00".repeat(64))))
        assertFalse(Nip01Protocol.verifyBulletin(event.copy(content = "a".repeat(2049))))
        assertFalse(Nip01Protocol.verifyBulletin(event.copy(pubkey = "00".repeat(32))))
    }

    @Test fun importExplainsPlainTextAndRejectsTampering() {
        val plainTextError = assertThrows(IllegalArgumentException::class.java) {
            Nip01Protocol.parseImportedBulletin("Help is needed")
        }
        assertTrue(plainTextError.message.orEmpty().contains("Bulletin draft"))

        val signed = Nip01Protocol.signBulletin(hex("00".repeat(31) + "03"), "Help is needed", 1_700_000_000)
        assertEquals(signed, Nip01Protocol.parseImportedBulletin(Nip01Protocol.toJson(signed)))
        val changed = Nip01Protocol.toJson(signed).replace("Help is needed", "Help is here")
        assertThrows(IllegalArgumentException::class.java) { Nip01Protocol.parseImportedBulletin(changed) }
    }
}
