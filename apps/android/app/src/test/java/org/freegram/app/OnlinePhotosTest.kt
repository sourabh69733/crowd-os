package org.freegram.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.freegram.app.identity.ProtectedIdentity
import org.freegram.app.identity.SecretWrapper
import org.freegram.app.media.*
import org.freegram.app.protocol.*
import org.freegram.app.store.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnlinePhotosTest {
    @Test fun carrierUploadsWithoutChangingPostAndReceiptSurvivesRecreation() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("freegram_media_uploads", 0).edit().clear().commit()
        val store = RoomStore(context, "photos-${UUID.randomUUID()}")
        store.initialize()
        val bytes = javaClass.classLoader!!.getResourceAsStream("photo.jpg")!!.readBytes()
        val ref = PhotoRef(sha256Hex(bytes), bytes.size, 1, 1)
        val event = Nip01Protocol.signBulletin(ByteArray(32) { 2 }, "carried", 10, arrayOf(ref.toTag()))
        store.saveReceivedEvent(event)
        val media = MediaStore(File(context.cacheDir, UUID.randomUUID().toString()))
        media.put(ref.sha256, bytes, setOf(ref.sha256))
        val identity = ProtectedIdentity(context, object : SecretWrapper {
            override fun wrap(secret: ByteArray) = secret.copyOf()
            override fun unwrap(blob: ByteArray) = blob.copyOf()
        })
        var uploads = 0
        var downloads = 0
        val client = BlossomClient(OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "GET") {
                downloads++
                return@addInterceptor Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
            }
            uploads++
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(201).message("OK")
                .body("""{"sha256":"${ref.sha256}","size":${ref.size},"url":"${BlossomClient.url(ref.sha256)}"}""".toResponseBody()).build()
        }.build())
        assertFalse(OnlinePhotos(context, store, media, identity, client).sync())
        assertFalse(OnlinePhotos(context, store, media, identity, client).sync())
        assertEquals(1, uploads)
        assertEquals(event, store.savedEvents().single())
        val revision = PhotoChanges.updates.value
        val downloadedMedia = MediaStore(File(context.cacheDir, UUID.randomUUID().toString()))
        assertFalse(OnlinePhotos(context, store, downloadedMedia, identity, client).sync())
        assertEquals(1, downloads)
        assertTrue(PhotoChanges.updates.value > revision)
        assertArrayEquals(bytes, downloadedMedia.read(ref.sha256))
        assertEquals(event, store.savedEvents().single())
        store.setAuthorState(event.pubkey, AuthorState.BLOCKED)
        assertFalse(OnlinePhotos(context, store, media, identity, client).sync())
        assertEquals(1, uploads)
        store.close()
    }
    @Test fun failedFirstBatchDoesNotStarveLaterPhotos() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("freegram_media_uploads", 0).edit().clear().commit()
        val store = RoomStore(context, "photos-${UUID.randomUUID()}")
        store.initialize()
        val media = MediaStore(File(context.cacheDir, UUID.randomUUID().toString()))
        repeat(9) { index ->
            val bytes = byteArrayOf(index.toByte())
            val ref = PhotoRef(sha256Hex(bytes), bytes.size, 1, 1)
            store.saveReceivedEvent(Nip01Protocol.signBulletin(ByteArray(32) { 3 }, "photo $index", index.toLong() + 10, arrayOf(ref.toTag())))
            media.put(ref.sha256, bytes, setOf(ref.sha256))
        }
        val last = PhotoRef.of(store.savedEvents().last())!!
        val seen = mutableSetOf<String>()
        val identity = ProtectedIdentity(context, object : SecretWrapper {
            override fun wrap(secret: ByteArray) = secret.copyOf()
            override fun unwrap(blob: ByteArray) = blob.copyOf()
        })
        val client = BlossomClient(OkHttpClient.Builder().addInterceptor { chain ->
            val hash = chain.request().header("X-SHA-256")!!
            seen.add(hash)
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (hash == last.sha256) 201 else 403).message("Test")
                .body("""{"sha256":"$hash","size":1,"url":"${BlossomClient.url(hash)}"}""".toResponseBody()).build()
        }.build())
        val sync = OnlinePhotos(context, store, media, identity, client)
        assertTrue(sync.sync())
        assertEquals(8, seen.size)
        assertTrue(sync.sync())
        assertEquals(9, seen.size)
        assertTrue(context.getSharedPreferences("freegram_media_uploads", 0).getStringSet("uploaded", emptySet())!!.contains(last.sha256))
        store.close()
    }

}
