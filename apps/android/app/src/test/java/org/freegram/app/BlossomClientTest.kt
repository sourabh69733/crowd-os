package org.freegram.app

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.freegram.app.media.BlossomClient
import org.freegram.app.media.sha256Hex
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BlossomClientTest {
    @Test fun uploadUsesScopedSignedAuthorizationAndChecksDescriptor() {
        val bytes = byteArrayOf(1, 2, 3)
        val ref = PhotoRef(sha256Hex(bytes), 3, 1, 1)
        var inspected = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val event = Nip01Protocol.fromJson(String(Base64.getUrlDecoder().decode(request.header("Authorization")!!.removePrefix("Nostr "))))
            assertTrue(Nip01Protocol.verifySigned(event, 8192))
            assertEquals(24242, event.kind)
            assertTrue(event.tags.any { it.contentEquals(arrayOf("x", ref.sha256)) })
            assertTrue(event.tags.any { it.contentEquals(arrayOf("server", "media.freegram.in")) })
            assertEquals(ref.sha256, request.header("X-SHA-256"))
            inspected = true
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(201).message("Created")
                .body("""{"sha256":"${ref.sha256}","size":3,"url":"${BlossomClient.url(ref.sha256)}"}""".toResponseBody()).build()
        }.build()
        assertTrue(BlossomClient(client).upload(ref, bytes) { tags -> Nip01Protocol.signEvent(ByteArray(32) { 1 }, 24242, "Upload photo", 10, tags) })
        assertTrue(inspected)
    }
    @Test fun downloadRejectsWrongHashAndOversizedBytes() {
        val ref = PhotoRef(sha256Hex(byteArrayOf(1, 2, 3)), 3, 1, 1)
        for (bytes in listOf(byteArrayOf(1, 2, 4), ByteArray(4))) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
            }.build()
            assertNull(BlossomClient(client).download(ref))
        }
    }
    @Test fun signedPhotoUrlRoundTripsWithoutChangingLegacyTags() {
        val old = PhotoRef("a".repeat(64), 10, 1, 2)
        val next = old.copy(url = BlossomClient.url(old.sha256))
        val event = Nip01Protocol.signBulletin(ByteArray(32) { 1 }, "photo", 10, arrayOf(next.toTag()))
        assertEquals(next, PhotoRef.of(event))
        assertFalse(old.toTag().any { it.startsWith("url ") })
        assertTrue(Nip01Protocol.verifyBulletin(event))
    }
}
