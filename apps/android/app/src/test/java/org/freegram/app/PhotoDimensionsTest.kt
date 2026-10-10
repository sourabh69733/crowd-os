package org.freegram.app

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.freegram.app.media.PhotoEncoder
import org.freegram.app.media.sha256Hex
import org.freegram.app.protocol.PhotoRef
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoDimensionsTest {
    @Test fun decodedDimensionsMustMatchSignedReference() {
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val bytes = out.toByteArray()
        val ref = PhotoRef(sha256Hex(bytes), bytes.size, 20, 30)
        assertTrue(PhotoEncoder.isValidPhoto(bytes, ref))
        assertFalse(PhotoEncoder.isValidPhoto(bytes, ref.copy(width = 21)))
        bitmap.recycle()
    }
}
