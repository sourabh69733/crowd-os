package org.freegram.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.freegram.app.protocol.VideoManifest

data class EncodedVideo(val bytes: ByteArray, val width: Int, val height: Int, val durationSeconds: Int)

@androidx.annotation.OptIn(UnstableApi::class)
object VideoEncoder {
    suspend fun encode(context: Context, uri: Uri): EncodedVideo {
        val source = File.createTempFile("video-source-", ".bin", context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { input -> source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= VideoManifest.MAX_SOURCE_BYTES) { "Choose a video smaller than 40 MB" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "Video is empty" }
                } }
            }
            val seconds = withContext(Dispatchers.IO) { metadata(source).third }
            require(seconds in 1..VideoManifest.MAX_DURATION_SECONDS) { "Choose a video up to 5 minutes long" }
            var lastFailure: Exception? = null
            for (height in listOf(720, 480)) {
                val exported = File.createTempFile("video-export-", ".mp4", context.cacheDir).also { it.delete() }
                val clean = File.createTempFile("video-clean-", ".mp4", context.cacheDir).also { it.delete() }
                try {
                    export(context, source, exported, height, seconds)
                    return withContext(Dispatchers.IO) {
                        require(exported.length() in 1..VideoManifest.MAX_SOURCE_BYTES.toLong()) { "Video export is too large" }
                        stripMetadata(exported, clean)
                        require(clean.length() in 1..VideoManifest.MAX_VIDEO_BYTES.toLong()) { "Video is too large after shrinking" }
                        val (width, actualHeight, duration) = metadata(clean)
                        require(width in 1..1280 && actualHeight in 1..1280 && minOf(width, actualHeight) <= 720) { "Unsupported video dimensions" }
                        EncodedVideo(clean.readBytes(), width, actualHeight, duration)
                    }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { lastFailure = failure }
                finally { exported.delete(); clean.delete() }
            }
            throw IllegalArgumentException("Could not shrink this video. Try a shorter clip.", lastFailure)
        } finally { source.delete() }
    }

    private suspend fun export(context: Context, source: File, output: File, height: Int, seconds: Int) = withContext(Dispatchers.Main) {
        var activeTransformer: Transformer? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var watchdog: Runnable? = null
        try { withTimeout(5 * 60_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val bitrate = minOf(1_500_000, (VideoManifest.MAX_VIDEO_BYTES.toLong() * 8 * 8 / 10 / seconds - 96_000).toInt()).coerceAtLeast(200_000)
                val encoder = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(96_000).build()).build()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder).addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            watchdog?.let(handler::removeCallbacks)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            watchdog?.let(handler::removeCallbacks)
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                activeTransformer = transformer
                continuation.invokeOnCancellation { watchdog?.let(handler::removeCallbacks) }
                watchdog = Runnable {
                    if (continuation.isActive) {
                        if (output.length() > VideoManifest.MAX_SOURCE_BYTES) {
                            transformer.cancel()
                            continuation.resumeWithException(IllegalArgumentException("Video export is too large"))
                        } else watchdog?.let { handler.postDelayed(it, 500) }
                    }
                }
                val effect = Presentation.createForWidthAndHeight(if (height == 720) 1280 else 854, height, Presentation.LAYOUT_SCALE_TO_FIT)
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).setEffects(Effects(emptyList(), listOf(effect))).build()
                try { transformer.start(edited, output.path); watchdog?.let { handler.postDelayed(it, 500) } }
                catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        } } finally { watchdog?.let(handler::removeCallbacks); activeTransformer?.cancel() }
    }

    fun isValid(file: File, ref: org.freegram.app.protocol.VideoRef): Boolean = runCatching {
        if (file.length() != ref.size.toLong()) return@runCatching false
        val (width, height, seconds) = metadata(file)
        if (width != ref.width || height != ref.height || seconds != ref.durationSeconds) return@runCatching false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) }
            "video/avc" in types && types.all { it == "video/avc" || it == "audio/mp4a-latm" }
        } finally { extractor.release() }
    }.getOrDefault(false)

    private fun metadata(file: File): Triple<Int, Int, Int> {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.path)
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            Triple(width, height, ((duration + 999) / 1000).toInt())
        } finally { metadata.release() }
    }

    /** Rebuild the container from audio/video samples without location, camera or user metadata. */
    private suspend fun stripMetadata(source: File, target: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.path)
            val tracks = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime == "video/avc" || mime == "audio/mp4a-latm") {
                    tracks[index] = muxer.addTrack(format); extractor.selectTrack(index)
                }
            }
            require(tracks.isNotEmpty()) { "Unsupported video codec" }
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                require(extractor.sampleSize <= buffer.capacity()) { "Unsupported video sample" }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity() && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Unsupported video sample" }
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                extractor.advance()
            }
            muxer.stop(); started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release(); extractor.release()
        }
    }
}
