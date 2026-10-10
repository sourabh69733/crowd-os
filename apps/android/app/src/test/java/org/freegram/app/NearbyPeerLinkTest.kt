package org.freegram.app

import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.freegram.app.nearby.NearbyPeerLink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyPeerLinkTest {
    private val context = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private val client = Proxy.newProxyInstance(ConnectionsClient::class.java.classLoader, arrayOf(ConnectionsClient::class.java)) { _, method, _ ->
        calls += method.name
        if (method.returnType == java.lang.Void.TYPE) null else Tasks.forResult<Void>(null)
    } as ConnectionsClient
    private fun update(id: Long, size: Long) = PayloadTransferUpdate.Builder().setPayloadId(id).setStatus(PayloadTransferUpdate.Status.SUCCESS)
        .setBytesTransferred(size).setTotalBytes(size).build()

    @Test fun requestedFileCompletesInEitherCallbackOrderAndIsDeleted() = runBlocking<Unit> {
        for (successFirst in listOf(false, true)) {
            val file = File(context.cacheDir, "incoming-${UUID.randomUUID()}.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val payload = Payload.fromFile(file)
            val link = NearbyPeerLink(context, client, "peer", filesEnabled = true)
            link.expectFile(payload.id, 3)
            if (successFirst) link.payloadCallback.onPayloadTransferUpdate("peer", update(payload.id, 3))
            link.payloadCallback.onPayloadReceived("peer", payload)
            if (!successFirst) link.payloadCallback.onPayloadTransferUpdate("peer", update(payload.id, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), link.receiveFile(payload.id))
            assertFalse(file.exists())
            link.disconnected()
        }
    }
    @Test fun unsolicitedFileDisconnectsAndCancelsPayload() {
        val file = File(context.cacheDir, "unwanted-${UUID.randomUUID()}.jpg").also { it.writeBytes(byteArrayOf(1)) }
        val link = NearbyPeerLink(context, client, "peer", filesEnabled = true)
        link.payloadCallback.onPayloadReceived("peer", Payload.fromFile(file))
        assertTrue("cancelPayload" in calls)
        assertTrue("disconnectFromEndpoint" in calls)
        assertFalse(file.exists())
    }
    @Test fun stopDisposesStagedFilesAndRejectsNewWork() {
        val file = File(context.cacheDir, "outgoing-${UUID.randomUUID()}.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val link = NearbyPeerLink(context, client, "peer", filesEnabled = true)
        link.prepareFile(file)
        val dir = File(context.cacheDir, "nearby-files")
        assertTrue(dir.listFiles().orEmpty().isNotEmpty())
        link.disconnected()
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertThrows(IllegalStateException::class.java) { link.prepareFile(file) }
        assertThrows(IllegalStateException::class.java) { link.expectFile(42, 3) }
        file.delete()
    }

}
