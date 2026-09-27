package org.freegram.app.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import org.freegram.app.protocol.PhotoRef

data class EncodedPhoto(val bytes: ByteArray, val ref: PhotoRef)

/**
 * Decodes a picked image and re-encodes it as a fresh JPEG. Re-encoding keeps only pixels, so
 * EXIF metadata such as GPS position, camera and time is not carried into the post.
 */
object PhotoEncoder {
    fun encode(resolver: ContentResolver, uri: Uri): EncodedPhoto = encode(decode(resolver, uri))

    fun encode(source: Bitmap): EncodedPhoto {
        val scale = PhotoRef.MAX_DIMENSION.toFloat() / max(source.width, source.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1), (source.height * scale).roundToInt().coerceAtLeast(1), true)
        } else source
        for (quality in listOf(85, 75, 65, 55, 45)) {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
            val bytes = out.toByteArray()
            if (bytes.size <= PhotoRef.MAX_BYTES) {
                return EncodedPhoto(bytes, PhotoRef(sha256Hex(bytes), bytes.size, bitmap.width, bitmap.height))
            }
        }
        throw IllegalArgumentException("Photo is too detailed to fit in 1 MB")
    }

    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > PhotoRef.MAX_DIMENSION * 2) decoder.setTargetSampleSize(longest / (PhotoRef.MAX_DIMENSION * 2))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / (PhotoRef.MAX_DIMENSION * 2))
            }
            requireNotNull(resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }) { "Not an image" }
        }

    /** Checks received bytes really are a JPEG within the photo limits, without trusting the sender. */
    fun isValidPhoto(bytes: ByteArray, ref: PhotoRef): Boolean {
        if (bytes.size != ref.size || bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..PhotoRef.MAX_DIMENSION && bounds.outHeight in 1..PhotoRef.MAX_DIMENSION
    }
}
