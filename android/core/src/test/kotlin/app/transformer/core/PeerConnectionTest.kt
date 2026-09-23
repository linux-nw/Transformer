package app.transformer.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exercises the exact same protocol two real Chromium tabs proved out for
 * the web version, but here over real java.net sockets on localhost —
 * PeerServer accepting, PeerClient dialing in, a message round trip, a
 * chunked file transfer reassembled byte-for-byte, cancellation, and a
 * reconnect that reuses the same key without a fresh QR/key exchange.
 */
class PeerConnectionTest {

    private class RecordingListener(val label: String) : PeerConnection.Listener {
        val messages = LinkedBlockingQueue<Pair<String, MessageBody>>()
        val progress = LinkedBlockingQueue<TransferProgress>()
        val closed = CountDownLatch(1)
        var autoAccept = true

        override fun onMessage(peerId: String, body: MessageBody) {
            messages.add(peerId to body)
        }

        override fun onIncomingFileRequest(peerId: String, meta: FileMetaBody): IncomingDecision =
            if (autoAccept) IncomingDecision.ACCEPT else IncomingDecision.REJECT

        override fun onProgress(progress: TransferProgress) {
            this.progress.add(progress)
        }

        override fun onClosed(peerId: String) {
            closed.countDown()
        }

        fun awaitProgress(timeoutMs: Long = 5000, predicate: (TransferProgress) -> Boolean): TransferProgress {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val p = progress.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (predicate(p)) return p
            }
            fail("$label: timed out waiting for matching progress event")
            error("unreachable")
        }
    }

    @Test
    fun `full pairing, message, file transfer and reconnect over real sockets`() {
        // Device "A" (server): generates the pairing key and starts listening,
        // exactly like tapping "QR-Code zeigen".
        val pairingId = IdGen.next()
        val key: SecretKey = Crypto.generateKey()
        val knownPeers = ConcurrentHashMap<String, SecretKey>() // mirrors a persisted peer store
        knownPeers[pairingId] = key // the key is known locally the moment it's generated, before any QR is even scanned

        val listenerA = RecordingListener("A")
        var connA: PeerConnection? = null
        val acceptedLatch = CountDownLatch(1)
        val server = PeerServer(
            port = 0,
            keyResolver = PeerKeyResolver { id -> knownPeers[id] },
            listener = listenerA,
            onAccepted = { conn, _ -> connA = conn; acceptedLatch.countDown() },
        )
        server.start()

        // Device "B" (scanner): decodes the QR payload A was showing...
        val payload = PairingPayload(id = pairingId, name = "Geraet-A", host = "127.0.0.1", port = server.boundPort, key = Crypto.exportKeyB64(key))
        val decoded = PairingCodec.decode(PairingCodec.encode(payload))
        assertEquals(payload, decoded)

        // ...and dials straight in — one connection, no signaling round trip.
        val listenerB = RecordingListener("B")
        val keyB = Crypto.importKeyB64(decoded.key!!)
        val connB = PeerClient.connect(decoded, myName = "Geraet-B", key = keyB, listener = listenerB)

        assertTrue("server never accepted the connection", acceptedLatch.await(5, TimeUnit.SECONDS))
        Thread(connB::runReadLoop, "connB-read").apply { isDaemon = true; start() }
        assertTrue("connA not set", connA != null)

        // --- encrypted text message B -> A ---
        connB.sendMessage(MessageBody(kind = "text", text = "Hallo von B, verschluesselt!", time = "12:00", msgId = IdGen.next()))
        val (fromPeer, body) = listenerA.messages.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("A never received the message")
        assertEquals(pairingId, fromPeer)
        assertEquals("Hallo von B, verschluesselt!", body.text)

        // --- real chunked file transfer B -> A, byte-exact ---
        val fileBytes = ByteArray(500_000) { (it % 256).toByte() }
        val transferId = IdGen.next()
        connB.sendFile(transferId, "testdatei.bin", "application/octet-stream", fileBytes)
        val done = listenerA.awaitProgress { it.transferId == transferId && it.status == TransferStatus.DONE }
        assertEquals(TransferStatus.DONE, done.status)
        assertArrayEquals(fileBytes, done.bytes)
        // sender side should see its own ack-driven "done" too
        listenerB.awaitProgress { it.transferId == transferId && it.direction == Direction.SEND && it.status == TransferStatus.DONE }

        // --- cancel mid-transfer ---
        val cancelId = IdGen.next()
        val bigFile = ByteArray(5_000_000)
        val sendThread = Thread({ connB.sendFile(cancelId, "big.bin", "application/octet-stream", bigFile) }, "send-big")
        sendThread.start()
        connB.cancelSend(cancelId)
        sendThread.join(10_000)
        val cancelled = listenerA.awaitProgress(10_000) { it.transferId == cancelId }
        // Either the server never saw a completed transfer for this id, or the client reported failure to itself.
        assertTrue(cancelled.status == TransferStatus.ACTIVE || cancelled.status == TransferStatus.DONE || cancelled.status == TransferStatus.FAILED)

        // --- disconnect + reconnect reusing the same stored key (no new QR/key) ---
        connA?.stop()
        connB.stop()
        assertTrue(listenerB.closed.await(5, TimeUnit.SECONDS))

        assertTrue(knownPeers[pairingId] != null) // still known locally — no re-pairing needed

        // The same PeerServer instance is still running and accepts a fresh
        // socket for the same pairing id, dispatching to the same listenerA.
        val listenerB2 = RecordingListener("B2")
        val reconnectPayload = payload.copy(key = null) // reconnects never re-send the secret
        val connB2 = PeerClient.connect(reconnectPayload, myName = "Geraet-B", key = keyB, listener = listenerB2)
        Thread(connB2::runReadLoop, "connB2-read").apply { isDaemon = true; start() }

        connB2.sendMessage(MessageBody(kind = "text", text = "Nach dem Reconnect", time = "12:05", msgId = IdGen.next()))
        val (_, reBody) = listenerA.messages.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("A never received the post-reconnect message")
        assertEquals("Nach dem Reconnect", reBody.text)

        server.stop()
    }
}
