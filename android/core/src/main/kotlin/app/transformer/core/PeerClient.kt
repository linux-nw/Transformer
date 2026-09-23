package app.transformer.core

import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.SecretKey

/** Dials out to a device whose QR code (or a stored, previously-paired
 * address) was just read. One connection attempt, no signaling round trip
 * needed — unlike the web version's WebRTC/SDP dance, a raw socket only
 * needs an IP and a port. */
object PeerClient {
    fun connect(
        payload: PairingPayload,
        myName: String,
        key: SecretKey,
        listener: PeerConnection.Listener,
        timeoutMs: Int = 5000,
    ): PeerConnection {
        val socket = Socket()
        socket.connect(InetSocketAddress(payload.host, payload.port), timeoutMs)
        val link = PeerSocketLink(socket)
        link.sendLine(PairingCodec.encodeHello(HelloBody(id = payload.id, name = myName)))
        return PeerConnection(payload.id, link, key, listener)
    }
}
