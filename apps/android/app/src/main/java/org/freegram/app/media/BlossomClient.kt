package org.freegram.app.media

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.freegram.app.protocol.BulletinEvent
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.PhotoRef

class BlossomClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()) {
    fun upload(ref: PhotoRef, bytes: ByteArray, sign: (Array<Array<String>>) -> BulletinEvent): Boolean {
        require(bytes.size == ref.size && sha256Hex(bytes) == ref.sha256)
        val event = sign(arrayOf(arrayOf("t", "upload"), arrayOf("x", ref.sha256), arrayOf("server", HOST),
            arrayOf("expiration", (System.currentTimeMillis() / 1000 + 120).toString())))
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(Nip01Protocol.toJson(event).toByteArray(Charsets.UTF_8))
        val request = Request.Builder().url("$BASE/upload").header("Authorization", "Nostr $token")
            .header("X-SHA-256", ref.sha256).put(bytes.toRequestBody(PhotoRef.MIME.toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body ?: return@use false
            val input = body.byteStream().readBytesBounded(8192) ?: return@use false
            val result = runCatching { Json.parseToJsonElement(String(input, Charsets.UTF_8)).jsonObject }.getOrNull() ?: return@use false
            result["sha256"]?.jsonPrimitive?.content == ref.sha256 &&
                result["size"]?.jsonPrimitive?.int == ref.size && result["url"]?.jsonPrimitive?.content == url(ref.sha256)
        }
    }

    fun download(ref: PhotoRef): ByteArray? {
        val request = Request.Builder().url(url(ref.sha256)).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.byteStream()?.readBytesBounded(ref.size) ?: return@use null
            bytes.takeIf { it.size == ref.size && sha256Hex(it) == ref.sha256 }
        }
    }

    companion object {
        const val HOST = "media.freegram.in"
        const val BASE = "https://$HOST"
        fun url(hash: String): String {
            require(hash.matches(Regex("[0-9a-f]{64}")))
            return "$BASE/$hash.jpg"
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) return null
        output.write(buffer, 0, count)
    }
}
