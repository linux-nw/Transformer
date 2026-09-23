package app.transformer.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.transformer.R
import app.transformer.core.Crypto
import app.transformer.core.Direction
import app.transformer.core.FileMetaBody
import app.transformer.core.IdGen
import app.transformer.core.IncomingDecision
import app.transformer.core.MessageBody
import app.transformer.core.PairingCodec
import app.transformer.core.PairingPayload
import app.transformer.core.PeerClient
import app.transformer.core.PeerConnection
import app.transformer.core.PeerKeyResolver
import app.transformer.core.PeerServer
import app.transformer.core.TransferProgress
import app.transformer.core.TransferStatus
import app.transformer.data.PeerStore
import app.transformer.data.StoredPeer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class SendResult(val queued: Boolean, val id: String)

/**
 * The one thing keeping Transformer honest as a "real" app instead of the
 * web prototype it started from: this Service runs in the foreground, so
 * the TCP server and NSD advertising/discovery keep working even while the
 * user is in a different app — messages and files can arrive, and queued
 * ones can flush the moment a peer reappears on the network, with nobody
 * having to keep this Activity on screen.
 */
class TransferService : Service() {

    inner class LocalBinder : Binder() {
        val service: TransferService get() = this@TransferService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var peerStore: PeerStore
    private lateinit var nsd: NsdCoordinator
    private var server: PeerServer? = null

    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val textOutbox = ConcurrentHashMap<String, MutableList<Pair<String, MessageBody>>>() // peerId -> [(msgId, body)]
    private val fileOutbox = ConcurrentHashMap<String, MutableList<QueuedFile>>()
    private val incomingFileDecisions = ConcurrentHashMap<String, CompletableDeferred<IncomingDecision>>()
    private var pendingOffer: PendingOffer? = null

    @Volatile private var cachedAutoAccept = true

    private data class QueuedFile(val transferId: String, val name: String, val mime: String, val bytes: ByteArray)
    private data class PendingOffer(val pairingId: String, val key: SecretKey)

    private val _connectionStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, Boolean>> = _connectionStatus.asStateFlow()

    private val _messages = MutableSharedFlow<Pair<String, MessageBody>>(extraBufferCapacity = 64)
    val messages: SharedFlow<Pair<String, MessageBody>> = _messages.asSharedFlow()

    private val _progress = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 256)
    val progress: SharedFlow<TransferProgress> = _progress.asSharedFlow()

    private val _incomingFileRequests = MutableSharedFlow<Pair<String, FileMetaBody>>(extraBufferCapacity = 16)
    val incomingFileRequests: SharedFlow<Pair<String, FileMetaBody>> = _incomingFileRequests.asSharedFlow()

    private val connectionListener = object : PeerConnection.Listener {
        override fun onMessage(peerId: String, body: MessageBody) {
            _messages.tryEmit(peerId to body)
        }

        override fun onIncomingFileRequest(peerId: String, meta: FileMetaBody): IncomingDecision {
            if (cachedAutoAccept) return IncomingDecision.ACCEPT
            val deferred = CompletableDeferred<IncomingDecision>()
            incomingFileDecisions[meta.transferId] = deferred
            _incomingFileRequests.tryEmit(peerId to meta)
            // Blocks only this connection's own dedicated read-loop thread —
            // other peers keep working, and the sender's TCP buffer simply
            // absorbs backpressure while we wait for the user to decide.
            return runBlocking { deferred.await() }
        }

        override fun onProgress(progress: TransferProgress) {
            _progress.tryEmit(progress)
        }

        override fun onClosed(peerId: String) {
            setStatus(peerId, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        peerStore = PeerStore(applicationContext)
        nsd = NsdCoordinator(applicationContext)

        peerStore.settings.onEach { cachedAutoAccept = it.autoAccept }.launchIn(scope)

        startForeground(NOTIFICATION_ID, buildNotification(0))
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        server?.stop()
        nsd.stopDiscovery()
        nsd.stopAllAdvertising()
        connections.values.forEach { it.stop() }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ startup

    private fun startServer() {
        val srv = PeerServer(
            port = 0,
            keyResolver = PeerKeyResolver { pairingId -> resolveKeySync(pairingId) },
            listener = connectionListener,
            onAccepted = { conn, remoteName -> adopt(conn, remoteName) },
        )
        srv.start()
        server = srv

        scope.launch {
            val peers = peerStore.peers.first()
            peers.forEach { nsd.advertise(it.id, srv.boundPort) }
        }

        nsd.startDiscovery { peerId, host, port ->
            scope.launch { maybeAutoReconnect(peerId, host, port) }
        }
    }

    private fun resolveKeySync(pairingId: String): SecretKey? {
        pendingOffer?.let { if (it.pairingId == pairingId) return it.key }
        return runBlocking { peerStore.peerById(pairingId) }?.let { Crypto.importKeyB64(it.keyB64) }
    }

    private suspend fun maybeAutoReconnect(peerId: String, host: String, port: Int) {
        if (connections[peerId]?.isOpen == true) return
        if (host == LocalAddress.currentIPv4() && port == server?.boundPort) return // mDNS found our own advertisement
        val stored = peerStore.peerById(peerId) ?: return // not one of ours
        val key = Crypto.importKeyB64(stored.keyB64)
        val payload = PairingPayload(id = peerId, name = stored.name, host = host, port = port)
        runCatching {
            PeerClient.connect(payload, myName = peerStore.settings.first().myDeviceName, key = key, listener = connectionListener)
        }.onSuccess { conn ->
            adopt(conn)
            peerStore.updateLastAddress(peerId, host, port)
            Thread(conn::runReadLoop, "conn-$peerId-nsd").apply { isDaemon = true; start() }
        }
    }

    /** [remoteName] is only known when this came from [PeerServer.onAccepted]
     * (the accepting side learns it from the hello preamble); dialed-out
     * connections already know who they called and persist separately. */
    private fun adopt(conn: PeerConnection, remoteName: String? = null) {
        connections[conn.peerId]?.let { if (it !== conn) it.stop() }
        connections[conn.peerId] = conn
        setStatus(conn.peerId, true)
        val port = server?.boundPort
        if (port != null) nsd.advertise(conn.peerId, port)
        flushOutbox(conn.peerId)

        val pending = pendingOffer
        if (remoteName != null && pending != null && pending.pairingId == conn.peerId) {
            pendingOffer = null
            scope.launch {
                peerStore.upsertPeer(StoredPeer(id = conn.peerId, name = remoteName, keyB64 = Crypto.exportKeyB64(pending.key)))
            }
        }
    }

    private fun setStatus(peerId: String, online: Boolean) {
        _connectionStatus.value = _connectionStatus.value.toMutableMap().apply { put(peerId, online) }
        updateNotification()
    }

    private fun updateNotification() {
        val online = _connectionStatus.value.count { it.value }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(online))
    }

    private fun buildNotification(onlineCount: Int): Notification {
        ensureChannel()
        val text = if (onlineCount == 0) "Bereit — wartet auf Geräte im Netzwerk" else "$onlineCount Gerät(e) verbunden"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transformer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Übertragungen", NotificationManager.IMPORTANCE_LOW),
        )
    }

    // ------------------------------------------------------------ pairing

    /** Starts (or restarts) showing a QR offer. `reuseId` reconnects an
     * existing peer without generating a new shared key. */
    suspend fun createOfferPayload(reuseId: String? = null): String {
        val myName = peerStore.settings.first().myDeviceName
        val existing = reuseId?.let { peerStore.peerById(it) }
        val pairingId = existing?.id ?: IdGen.next()
        val key = existing?.let { Crypto.importKeyB64(it.keyB64) } ?: Crypto.generateKey()
        pendingOffer = PendingOffer(pairingId, key)
        val host = LocalAddress.currentIPv4() ?: "0.0.0.0"
        val port = server?.boundPort ?: 0
        val payload = PairingPayload(
            id = pairingId,
            name = myName,
            host = host,
            port = port,
            key = if (existing == null) Crypto.exportKeyB64(key) else null,
        )
        return PairingCodec.encode(payload)
    }

    fun cancelPendingOffer() {
        pendingOffer = null
    }

    sealed class ScanResult {
        data class Connected(val peerId: String, val name: String) : ScanResult()
        data class Error(val message: String) : ScanResult()
    }

    /** Scanned a QR code — dial straight in, one connection, no round trip. */
    suspend fun connectFromScan(text: String): ScanResult {
        val payload = try {
            PairingCodec.decode(text)
        } catch (e: Exception) {
            return ScanResult.Error("Kein gültiger Transformer-Code")
        }
        val key = try {
            if (payload.key != null) {
                Crypto.importKeyB64(payload.key)
            } else {
                val stored = peerStore.peerById(payload.id) ?: return ScanResult.Error("Unbekanntes Gerät — bitte neu koppeln")
                Crypto.importKeyB64(stored.keyB64)
            }
        } catch (e: Exception) {
            return ScanResult.Error("Ungültiger Schlüssel im Code")
        }
        val myName = peerStore.settings.first().myDeviceName
        return try {
            val conn = PeerClient.connect(payload, myName = myName, key = key, listener = connectionListener)
            adopt(conn)
            peerStore.upsertPeer(
                StoredPeer(id = payload.id, name = payload.name, keyB64 = Crypto.exportKeyB64(key), lastHost = payload.host, lastPort = payload.port),
            )
            pendingOffer = null
            Thread(conn::runReadLoop, "conn-${payload.id}-scan").apply { isDaemon = true; start() }
            ScanResult.Connected(payload.id, payload.name)
        } catch (e: Exception) {
            ScanResult.Error("Verbindung fehlgeschlagen: ${e.message}")
        }
    }

    // -------------------------------------------------------------- send

    fun sendMessage(peerId: String, body: MessageBody): SendResult {
        val msgId = body.msgId
        val conn = connections[peerId]
        if (conn != null && conn.isOpen) {
            conn.sendMessage(body)
        } else {
            textOutbox.getOrPut(peerId) { mutableListOf() }.add(msgId to body)
        }
        return SendResult(queued = conn == null || !conn.isOpen, id = msgId)
    }

    fun sendFile(peerId: String, name: String, mime: String, bytes: ByteArray): SendResult {
        val transferId = IdGen.next()
        val conn = connections[peerId]
        if (conn != null && conn.isOpen) {
            scope.launch(Dispatchers.IO) { conn.sendFile(transferId, name, mime, bytes) }
        } else {
            fileOutbox.getOrPut(peerId) { mutableListOf() }.add(QueuedFile(transferId, name, mime, bytes))
            _progress.tryEmit(TransferProgress(peerId, transferId, Direction.SEND, name, bytes.size.toLong(), TransferStatus.WAITING, 0))
        }
        return SendResult(queued = conn == null || !conn.isOpen, id = transferId)
    }

    private fun flushOutbox(peerId: String) {
        val conn = connections[peerId] ?: return
        textOutbox.remove(peerId)?.forEach { (_, body) -> conn.sendMessage(body) }
        fileOutbox.remove(peerId)?.forEach { q ->
            scope.launch(Dispatchers.IO) { conn.sendFile(q.transferId, q.name, q.mime, q.bytes) }
        }
    }

    fun cancelTransfer(peerId: String, transferId: String) {
        val queued = fileOutbox[peerId]?.removeAll { it.transferId == transferId } ?: false
        if (queued) {
            _progress.tryEmit(TransferProgress(peerId, transferId, Direction.SEND, "", 0, TransferStatus.FAILED, 0, reason = "Vom Nutzer abgebrochen"))
            return
        }
        connections[peerId]?.cancelSend(transferId)
    }

    fun resolveIncomingFile(transferId: String, decision: IncomingDecision) {
        incomingFileDecisions.remove(transferId)?.complete(decision)
    }

    // ---------------------------------------------------------- devices

    suspend fun renamePeer(peerId: String, name: String) = peerStore.renamePeer(peerId, name)

    suspend fun forgetPeer(peerId: String) {
        connections.remove(peerId)?.stop()
        nsd.stopAdvertising(peerId)
        peerStore.removePeer(peerId)
        _connectionStatus.value = _connectionStatus.value.toMutableMap().apply { remove(peerId) }
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            context.startForegroundService(intent)
        }
    }
}
