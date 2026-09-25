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

/** The exportable Nostr secret is wrapped by Android Keystore AES. This is not hardware Schnorr signing. */
class ProtectedIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("freegram_identity", Context.MODE_PRIVATE)
    private val alias = "freegram_nostr_wrap_v1"

    @Synchronized fun <T> withSecret(action: (ByteArray) -> T): T {
        val secret = loadOrCreate()
        return try { action(secret) } finally { secret.fill(0) }
    }

    private fun loadOrCreate(): ByteArray {
        val key = wrappingKey()
        val saved = prefs.getString("wrapped_secret", null)
        if (saved != null) {
            val blob = Base64.decode(saved, Base64.NO_WRAP)
            require(blob.size >= 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            return cipher.doFinal(blob.copyOfRange(12, blob.size)).also { require(it.size == 32) }
        }
        val secret = ByteArray(32)
        do {
            SecureRandom().nextBytes(secret)
            val valid = try { Secp256k1.pubkeyCreate(secret); true } catch (_: Exception) { false }
        } while (!valid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val blob = cipher.iv + cipher.doFinal(secret)
        check(prefs.edit().putString("wrapped_secret", Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
        return secret
    }

    private fun wrappingKey(): SecretKey {
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
