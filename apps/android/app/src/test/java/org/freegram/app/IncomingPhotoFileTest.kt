package org.freegram.app

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.freegram.app.nearby.IncomingPhotoFile
import org.junit.Assert.*
import org.junit.Test

class IncomingPhotoFileTest {
    @Test fun fileAndSuccessCanArriveInEitherOrderAndCleanupRuns() = runBlocking<Unit> {
        for (successFirst in listOf(false, true)) {
            val slot = IncomingPhotoFile()
            var cleaned = 0
            slot.expect(42, 3)
            if (successFirst) slot.update(42, 3, 3, true)
            assertTrue(slot.file(42, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, { cleaned++ }))
            if (!successFirst) slot.update(42, 3, 3, true)
            assertArrayEquals(byteArrayOf(1, 2, 3), slot.receive(42))
            assertEquals(1, cleaned)
        }
    }
    @Test fun unsolicitedDuplicateAndOversizedFilesAreRejected() = runBlocking<Unit> {
        val slot = IncomingPhotoFile()
        var cleaned = 0
        assertFalse(slot.file(9, { ByteArrayInputStream(byteArrayOf(1)) }, { cleaned++ }))
        slot.expect(42, 3)
        assertTrue(slot.file(42, { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }, { cleaned++ }))
        assertFalse(slot.file(42, { ByteArrayInputStream(byteArrayOf(1)) }, { cleaned++ }))
        slot.update(42, 3, 3, true)
        assertThrows(java.io.IOException::class.java) { runBlocking { slot.receive(42) } }
        assertEquals(3, cleaned)
        assertThrows(IllegalStateException::class.java) { slot.expect(42, 3) }
    }
    @Test fun cancelAndInvalidProgressDisposeTemporaryFiles() = runBlocking<Unit> {
        val slot = IncomingPhotoFile()
        var cleaned = false
        slot.expect(42, 3)
        slot.file(42, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, { cleaned = true })
        slot.update(42, 4, 4, null)
        assertThrows(java.io.IOException::class.java) { runBlocking { slot.receive(42) } }
        assertTrue(cleaned)
        slot.expect(43, 3)
        slot.cancel()
        assertThrows(java.io.IOException::class.java) { runBlocking { slot.receive(43) } }
    }
}
