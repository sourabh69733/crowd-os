package org.freegram.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.freegram.app.media.VideoEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoEncoderCancellationTest {
    @Test fun cancelDuringCopyRemovesOwnedSourceFile() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://test/video-cancel")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = object : ByteArrayInputStream(ByteArray(1024)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, input)
        fun sources() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("video-source-") }.map { it.name }.toSet()
        val before = sources()
        val job = launch(Dispatchers.Default) { VideoEncoder.encode(context, uri) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertEquals(before, sources())
    }
}
