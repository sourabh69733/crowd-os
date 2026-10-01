package org.freegram.app.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.acinq.secp256k1.Secp256k1
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import com.vitorpamplona.quartz.nip49PrivKeyEnc.Nip49
import org.freegram.app.protocol.Nip19

/** Encrypts the stored Nostr secret at rest. */
interface SecretWrapper {
    fun wrap(secret: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}

/** Android Keystore AES-GCM wrapping. This is not hardware Schnorr signing. */
class KeystoreSecretWrapper(private val alias: String = DEFAULT_ALIAS) : SecretWrapper {
    companion object { const val DEFAULT_ALIAS = "freegram_nostr_wrap_v1" }

    override fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(secret)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size >= 13)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * The exportable Nostr secret, wrapped at rest. Backup is a user-held NIP-19 `nsec`;
 * replacing the key cannot revoke the old one, because Nostr has no revocation.
 */
class ProtectedIdentity(context: Context, private val wrapper: SecretWrapper = KeystoreSecretWrapper()) {
    private val prefs = context.getSharedPreferences("freegram_identity", Context.MODE_PRIVATE)

    @Synchronized fun <T> withSecret(action: (ByteArray) -> T): T {
        val secret = loadOrCreate()
        return try { action(secret) } finally { secret.fill(0) }
    }

    fun publicKeyHex(): String = withSecret { publicKey(it) }
    fun npub(): String = Nip19.encodePublicKey(hexToBytes(publicKeyHex()))

    /** Backup text. Callers must not log it or place it on the clipboard. */
    fun exportNsec(): String = withSecret { Nip19.encodeSecretKey(it) }

    /**
     * Password-encrypted backup (NIP-49 `ncryptsec`, scrypt with 2^16 work). Safer to copy or store than an
     * `nsec`, but only as strong as the password.
     */
    fun exportEncrypted(password: String): String {
        require(password.length >= MIN_PASSWORD) { "Use a password of at least $MIN_PASSWORD characters" }
        return withSecret { Nip49().encrypt(it, password, 16, Nip49.EncryptedInfo.CLIENT_DOES_NOT_TRACK) }
    }

    /** Restores from an `ncryptsec` backup. A wrong password or damaged backup leaves the current key unchanged. */
    @Synchronized fun restoreEncrypted(ncryptsec: String, password: String): String {
        val hexKey = try { Nip49().decrypt(ncryptsec.trim(), password) } catch (failure: Exception) {
            throw IllegalArgumentException("Wrong password or damaged backup", failure)
        }
        return restore(Nip19.encodeSecretKey(ByteArray(32) { hexKey.substring(it * 2, it * 2 + 2).toInt(16).toByte() }))
    }

    /** Replaces this phone's key with a backed-up one. Returns the restored public key. */
    @Synchronized fun restore(nsec: String): String {
        val secret = Nip19.decodeSecretKey(nsec)
        try {
            val pubkey = publicKey(secret)
            store(secret)
            return pubkey
        } finally { secret.fill(0) }
    }

    /** Replaces this phone's key with a new random one, e.g. after suspected compromise. */
    @Synchronized fun replaceWithNewKey(): String {
        val secret = newSecret()
        try {
            store(secret)
            return publicKey(secret)
        } finally { secret.fill(0) }
    }

    private fun loadOrCreate(): ByteArray {
        val saved = prefs.getString("wrapped_secret", null)
        if (saved != null) {
            return wrapper.unwrap(Base64.decode(saved, Base64.NO_WRAP)).also { require(it.size == 32) }
        }
        return newSecret().also(::store)
    }

    private fun store(secret: ByteArray) {
        val blob = wrapper.wrap(secret)
        check(prefs.edit().putString("wrapped_secret", Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
    }

    private fun newSecret(): ByteArray {
        val secret = ByteArray(32)
        do {
            SecureRandom().nextBytes(secret)
            val valid = try { Secp256k1.pubkeyCreate(secret); true } catch (_: Exception) { false }
        } while (!valid)
        return secret
    }

    private fun publicKey(secret: ByteArray): String {
        val point = try { Secp256k1.pubkeyCreate(secret) } catch (failure: Exception) {
            throw IllegalArgumentException("Secret key is not a valid secp256k1 key", failure)
        }
        return point.copyOfRange(1, 33).joinToString("") { "%02x".format(it) }
    }

    companion object { const val MIN_PASSWORD = 10 }

    private fun hexToBytes(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
