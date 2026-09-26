package org.freegram.app

import android.content.Context
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.identity.SecretWrapper
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.Nip19
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IdentityBackupTest {
    /** Robolectric has no AndroidKeyStore; XOR keeps the stored blob different from the secret. */
    private class TestWrapper : SecretWrapper {
        override fun wrap(secret: ByteArray) = ByteArray(secret.size) { (secret[it].toInt() xor 0x5a).toByte() }
        override fun unwrap(blob: ByteArray) = wrap(blob)
    }

    private lateinit var context: Context
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("freegram_identity", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Examples from https://github.com/nostr-protocol/nips/blob/master/19.md
    @Test fun officialNip19Examples() {
        val pub = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
        val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
        val sec = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
        val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        assertEquals(npub, Nip19.encodePublicKey(hex(pub)))
        assertArrayEquals(hex(pub), Nip19.decodePublicKey(npub))
        assertEquals(nsec, Nip19.encodeSecretKey(hex(sec)))
        assertArrayEquals(hex(sec), Nip19.decodeSecretKey(nsec))
        assertArrayEquals(hex(sec), Nip19.decodeSecretKey(nsec.uppercase()))
    }

    @Test fun rejectsWrongPrefixChecksumAndMixedCase() {
        val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        assertThrows(IllegalArgumentException::class.java) { Nip19.decodePublicKey(nsec) }
        assertThrows(IllegalArgumentException::class.java) { Nip19.decodeSecretKey(nsec.dropLast(1) + "6") }
        assertThrows(IllegalArgumentException::class.java) { Nip19.decodeSecretKey("nsec1" + nsec.drop(5).replaceFirst("v", "V")) }
        assertThrows(IllegalArgumentException::class.java) { Nip19.decodeSecretKey("nsec1qqqqqqqqqq") }
    }

    @Test fun backupRestoresSameAuthorOnSecondDevice() {
        val phoneA = ProtectedIdentity(context, TestWrapper())
        val authorA = phoneA.publicKeyHex()
        val backup = phoneA.exportNsec()
        assertEquals(authorA, phoneA.publicKeyHex())

        context.getSharedPreferences("freegram_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val phoneB = ProtectedIdentity(context, TestWrapper())
        assertNotEquals(authorA, phoneB.publicKeyHex())
        assertEquals(authorA, phoneB.restore(backup))
        assertEquals(authorA, ProtectedIdentity(context, TestWrapper()).publicKeyHex())

        val signed = phoneB.withSecret { Nip01Protocol.signBulletin(it, "restored", 1_700_000_000) }
        assertEquals(authorA, signed.pubkey)
        assertTrue(Nip01Protocol.verifyBulletin(signed))
    }

    @Test fun invalidRestoreKeepsCurrentKey() {
        val identity = ProtectedIdentity(context, TestWrapper())
        val before = identity.publicKeyHex()
        assertThrows(IllegalArgumentException::class.java) { identity.restore("not a key") }
        assertThrows(IllegalArgumentException::class.java) { identity.restore(Nip19.encodeSecretKey(ByteArray(32))) }
        assertEquals(before, identity.publicKeyHex())
    }

    @Test fun replacementCreatesNewKeyAndOldSignaturesStillVerify() {
        val identity = ProtectedIdentity(context, TestWrapper())
        val old = identity.withSecret { Nip01Protocol.signBulletin(it, "before", 1_700_000_000) }
        val replaced = identity.replaceWithNewKey()
        assertNotEquals(old.pubkey, replaced)
        assertEquals(replaced, identity.publicKeyHex())
        assertTrue(Nip01Protocol.verifyBulletin(old))
    }

    @Test fun storedBlobIsNotThePlainSecret() {
        val identity = ProtectedIdentity(context, TestWrapper())
        val secretHex = Nip19.decodeSecretKey(identity.exportNsec()).joinToString("") { "%02x".format(it) }
        val stored = context.getSharedPreferences("freegram_identity", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains(secretHex))
        assertFalse(stored.contains("nsec1"))
    }
}
