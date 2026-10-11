package org.freegram.app

import org.freegram.app.media.VideoStore
import org.freegram.app.media.sha256Hex
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.VideoManifest
import org.freegram.app.protocol.VideoRef
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VideoPiecesTest {
    private val secret = ByteArray(32) { 3 }
    @Test fun manifestBindsAuthorPostAndOrderedPieces() {
        val bytes = ByteArray(VideoManifest.PIECE_BYTES + 10) { (it % 251).toByte() }
        val manifest = VideoManifest.sign(secret, bytes, 640, 480, 10, 100)
        val post = Nip01Protocol.signBulletin(secret, "video", 100, arrayOf(manifest.ref.toTag()))
        assertTrue(manifest.matches(post))
        assertEquals(2, manifest.pieces.size)
        assertEquals(manifest.ref, VideoRef.of(post))
        assertNull(VideoManifest.of(manifest.event.copy(sig = "0".repeat(128))))
        assertFalse(manifest.matches(Nip01Protocol.signBulletin(ByteArray(32) { 4 }, "video", 100, arrayOf(manifest.ref.toTag()))))
    }
    @Test fun checkedPiecesSurviveRestartAndResumeFromAnotherSource() {
        val bytes = ByteArray(VideoManifest.PIECE_BYTES * 2 + 10) { (it % 251).toByte() }
        val manifest = VideoManifest.sign(secret, bytes, 640, 480, 10, 100)
        val dir = Files.createTempDirectory("video-pieces").toFile()
        try {
            val first = VideoStore(dir)
            first.putManifest(manifest)
            first.putPiece(manifest, 0, bytes.copyOfRange(0, VideoManifest.PIECE_BYTES))
            first.putPiece(manifest, 2, bytes.copyOfRange(VideoManifest.PIECE_BYTES * 2, bytes.size))
            val resumed = VideoStore(dir)
            assertEquals(listOf(1), resumed.missing(manifest))
            assertNull(resumed.assemble(manifest))
            resumed.putPiece(manifest, 1, bytes.copyOfRange(VideoManifest.PIECE_BYTES, VideoManifest.PIECE_BYTES * 2))
            assertEquals(emptyList<Int>(), resumed.missing(manifest))
            assertArrayEquals(bytes, resumed.assemble(manifest)!!.readBytes())
            assertEquals(manifest.event, resumed.manifest(manifest.event.id)!!.event)
            try { resumed.putPiece(manifest, 0, ByteArray(VideoManifest.PIECE_BYTES)); fail("Corrupt piece accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }
    @Test fun evictionRemovesOthersBeforeOwnSentAndProtectsOwnUnsent() {
        val dir = Files.createTempDirectory("video-budget").toFile()
        try {
            val store = VideoStore(dir, 12)
            val own = byteArrayOf(1,2,3,4)
            val other = byteArrayOf(5,6,7,8)
            val protected = byteArrayOf(9,10,11,12)
            val hashes = listOf(own, other, protected).map(::sha256Hex).toSet()
            store.putChecked(sha256Hex(own), own, hashes)
            store.putChecked(sha256Hex(other), other, hashes)
            store.putChecked(sha256Hex(protected), protected, hashes)
            val added = byteArrayOf(13,14,15,16)
            store.putChecked(sha256Hex(added), added, hashes, setOf(sha256Hex(own)), setOf(sha256Hex(protected)))
            assertNull(store.piece(sha256Hex(other)))
            assertNotNull(store.piece(sha256Hex(own)))
            assertNotNull(store.piece(sha256Hex(protected)))
        } finally { dir.deleteRecursively() }
    }
}
