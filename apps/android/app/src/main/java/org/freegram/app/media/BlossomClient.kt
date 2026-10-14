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
    fun upload(ref: PhotoRef, bytes: ByteArray, sign: (Array<Array<String>>) -> BulletinEvent): Boolean =
        uploadBlob(ref.sha256, ref.size, bytes, PhotoRef.MIME, "jpg", sign)

    fun uploadVideo(manifest: org.freegram.app.protocol.VideoManifest, bytes: ByteArray, sign: (Array<Array<String>>) -> BulletinEvent): Boolean =
        uploadBlob(manifest.ref.sha256, manifest.ref.size, bytes, "video/mp4", "mp4", sign)

    fun uploadManifest(manifest: org.freegram.app.protocol.VideoManifest, sign: (Array<Array<String>>) -> BulletinEvent): Boolean {
        val bytes = Nip01Protocol.toJson(manifest.event).toByteArray(Charsets.UTF_8)
        return uploadBlob(manifest.ref.manifestHash, bytes.size, bytes, "application/json", "json", sign)
    }

    private fun uploadBlob(hash: String, size: Int, bytes: ByteArray, mime: String, extension: String,
        sign: (Array<Array<String>>) -> BulletinEvent): Boolean {
        require(bytes.size == size && sha256Hex(bytes) == hash)
        val event = sign(arrayOf(arrayOf("t", "upload"), arrayOf("x", hash), arrayOf("server", HOST),
            arrayOf("expiration", (System.currentTimeMillis() / 1000 + 120).toString())))
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(Nip01Protocol.toJson(event).toByteArray(Charsets.UTF_8))
        val request = Request.Builder().url("$BASE/upload").header("Authorization", "Nostr $token")
            .header("X-SHA-256", hash).put(bytes.toRequestBody(mime.toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body ?: return@use false
            val input = body.byteStream().readBytesBounded(8192) ?: return@use false
            val result = runCatching { Json.parseToJsonElement(String(input, Charsets.UTF_8)).jsonObject }.getOrNull() ?: return@use false
            result["sha256"]?.jsonPrimitive?.content == hash &&
                result["size"]?.jsonPrimitive?.int == size && result["url"]?.jsonPrimitive?.content == "$BASE/$hash.$extension"
        }
    }

    fun download(ref: PhotoRef): ByteArray? = downloadBlob(ref.sha256, ref.size, "jpg")
    fun downloadVideo(ref: org.freegram.app.protocol.VideoRef): ByteArray? = downloadBlob(ref.sha256, ref.size, "mp4")
    fun downloadPiece(manifest: org.freegram.app.protocol.VideoManifest, index: Int): ByteArray? {
        val size = manifest.pieceSize(index)
        val start = index.toLong() * org.freegram.app.protocol.VideoManifest.PIECE_BYTES
        val end = start + size - 1
        val request = Request.Builder().url("$BASE/${manifest.ref.sha256}.mp4").header("Range", "bytes=$start-$end").get().build()
        return client.newCall(request).execute().use { response ->
            if (response.code != 206 || response.header("Content-Range") != "bytes $start-$end/${manifest.ref.size}") return@use null
            val bytes = response.body?.byteStream()?.readBytesBounded(size) ?: return@use null
            bytes.takeIf { it.size == size && sha256Hex(it) == manifest.pieces[index] }
        }
    }
    fun downloadManifest(ref: org.freegram.app.protocol.VideoRef): org.freegram.app.protocol.VideoManifest? {
        val bytes = downloadBlob(ref.manifestHash, org.freegram.app.protocol.VideoManifest.MAX_BYTES, "json", exact = false) ?: return null
        val manifest = runCatching { org.freegram.app.protocol.VideoManifest.of(Nip01Protocol.fromJson(String(bytes, Charsets.UTF_8), org.freegram.app.protocol.VideoManifest.MAX_BYTES)) }.getOrNull()
        return manifest?.takeIf { it.ref == ref }
    }
    private fun downloadBlob(hash: String, limit: Int, extension: String, exact: Boolean = true): ByteArray? {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val request = Request.Builder().url("$BASE/$hash.$extension").get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > limit) return@use null
            val bytes = body.byteStream().readBytesBounded(limit) ?: return@use null
            bytes.takeIf { (!exact || it.size == limit) && sha256Hex(it) == hash }
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
