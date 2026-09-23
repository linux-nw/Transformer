package app.transformer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.transformer.core.Direction
import app.transformer.core.IdGen
import app.transformer.core.IncomingDecision
import app.transformer.core.MessageBody
import app.transformer.core.TransferProgress
import app.transformer.core.TransferStatus
import app.transformer.data.PeerStore
import app.transformer.net.TransferService
import app.transformer.qr.QrImage
import app.transformer.util.FileIo
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransformerViewModel(application: Application) : AndroidViewModel(application) {
    private val peerStore = PeerStore(application)
    private var service: TransferService? = null
    private var bound = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun now() = timeFmt.format(System.currentTimeMillis())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TransferService.LocalBinder).service
            service = svc
            bound = true
            observeService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    init {
        val app = getApplication<Application>()
        TransferService.start(app)
        app.bindService(Intent(app, TransferService::class.java), connection, Context.BIND_AUTO_CREATE)

        peerStore.settings.onEach { settings ->
            _state.update { it.copy(settings = settings) }
        }.launchIn(viewModelScope)

        peerStore.peers.onEach { peers ->
            _state.update { s ->
                val online = s.devices.associate { it.id to it.online }
                val devices = peers.map { DeviceUi(it.id, it.name, online[it.id] ?: false) }
                val active = s.activeDeviceId?.takeIf { id -> devices.any { it.id == id } } ?: devices.firstOrNull()?.id
                val screen = if (devices.isEmpty() && s.screen != Screen.PAIRING) Screen.PAIRING else s.screen
                s.copy(devices = devices, activeDeviceId = active, screen = screen)
            }
        }.launchIn(viewModelScope)
    }

    private fun observeService(svc: TransferService) {
        svc.connectionStatus.onEach { statusMap ->
            _state.update { s -> s.copy(devices = s.devices.map { it.copy(online = statusMap[it.id] ?: it.online) }) }
        }.launchIn(viewModelScope)

        svc.messages.onEach { (peerId, body) ->
            val entry = ChatMessageUi(
                id = "m-" + body.msgId, peerId = peerId, dir = "in", kind = body.kind,
                text = body.text, title = body.title, domain = body.domain, url = body.url, time = body.time,
            )
            _state.update { it.copy(messages = it.messages + entry) }
            if (_state.value.screen != Screen.HOME || _state.value.activeDeviceId != peerId) {
                val label = if (body.kind == "link") body.title else body.text
                showToast((deviceName(peerId) ?: "Nachricht") + ": " + label)
            }
        }.launchIn(viewModelScope)

        svc.incomingFileRequests.onEach { (peerId, meta) ->
            _state.update {
                it.copy(incomingFile = IncomingFileUi(peerId, deviceName(peerId) ?: "Gerät", meta.transferId, meta.name, FileIo.fmtBytes(meta.size)))
            }
        }.launchIn(viewModelScope)

        svc.progress.onEach { p -> onProgress(p) }.launchIn(viewModelScope)
    }

    private fun deviceName(peerId: String) = _state.value.devices.firstOrNull { it.id == peerId }?.name

    private fun onProgress(p: TransferProgress) {
        _state.update { s ->
            val idx = s.transfers.indexOfFirst { it.id == p.transferId }
            val prev = if (idx >= 0) s.transfers[idx] else TransferUi(p.transferId, p.peerId, p.name, "", "", if (p.direction == Direction.SEND) "send" else "receive", "active", 0)
            val next = prev.copy(
                name = p.name.ifEmpty { prev.name },
                size = if (p.size > 0) FileIo.fmtBytes(p.size) else prev.size,
                ext = FileIo.extOf(p.name).ifEmpty { prev.ext },
                dir = if (p.direction == Direction.SEND) "send" else "receive",
                status = p.status.name.lowercase(),
                progress = p.percent,
                reason = p.reason ?: prev.reason,
                downloadUri = prev.downloadUri,
            )
            val transfers = if (idx >= 0) s.transfers.toMutableList().apply { set(idx, next) } else s.transfers + next
            s.copy(transfers = transfers)
        }

        val receivedBytes = p.bytes
        if (p.status == TransferStatus.DONE && p.direction == Direction.RECEIVE && receivedBytes != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val app = getApplication<Application>()
                val uri = FileIo.saveToDownloads(app, p.name, p.mime ?: "application/octet-stream", receivedBytes)
                val isImage = (p.mime ?: "").startsWith("image/")
                val entry = ChatMessageUi(
                    id = "file-" + p.transferId, peerId = p.peerId, dir = "in",
                    kind = if (isImage) "images" else "file",
                    name = p.name, size = FileIo.fmtBytes(p.size), ext = FileIo.extOf(p.name),
                    time = now(), downloadUri = uri,
                )
                _state.update {
                    val transfers = it.transfers.map { t -> if (t.id == p.transferId) t.copy(downloadUri = uri) else t }
                    it.copy(messages = it.messages + entry, transfers = transfers)
                }
                showToast(p.name + " empfangen")
            }
        } else if (p.status == TransferStatus.DONE && p.direction == Direction.SEND) {
            showToast((p.name.ifEmpty { "Datei" }) + " gesendet")
        } else if (p.status == TransferStatus.FAILED) {
            showToast((p.name.ifEmpty { "Datei" }) + " fehlgeschlagen" + (p.reason?.let { ": $it" } ?: ""))
        }
    }

    private fun showToast(text: String) {
        _state.update { it.copy(toast = text) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    // ------------------------------------------------------------ nav

    fun goTo(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    fun selectDevice(id: String) {
        _state.update { it.copy(activeDeviceId = id) }
    }

    // --------------------------------------------------------- pairing

    fun setPairMode(mode: PairMode) {
        _state.update { it.copy(pairMode = mode, pairError = null) }
        val needsFreshOffer = mode == PairMode.SHOW && !_state.value.qrPending && _state.value.qrBitmap == null
        if (needsFreshOffer) startShowOffer(null)
    }

    fun startShowOffer(reuseId: String?) {
        _state.update { it.copy(qrPending = true, qrBitmap = null, pairError = null) }
        viewModelScope.launch {
            val svc = service ?: return@launch
            try {
                val payload = svc.createOfferPayload(reuseId)
                val bitmap = withContext(Dispatchers.Default) { QrImage.render(payload) }
                _state.update { it.copy(qrBitmap = bitmap, qrPending = false) }
            } catch (e: Exception) {
                _state.update { it.copy(qrPending = false, pairError = "Code konnte nicht erstellt werden: ${e.message}") }
            }
        }
    }

    fun reconnectDevice(id: String) {
        _state.update { it.copy(screen = Screen.PAIRING, pairMode = PairMode.SHOW, qrBitmap = null) }
        startShowOffer(id)
    }

    fun addDevice() {
        service?.cancelPendingOffer()
        _state.update { it.copy(screen = Screen.PAIRING, pairMode = PairMode.SHOW, qrBitmap = null) }
        startShowOffer(null)
    }

    fun onQrScanned(text: String) {
        viewModelScope.launch {
            val svc = service ?: return@launch
            when (val result = svc.connectFromScan(text)) {
                is TransferService.ScanResult.Connected -> {
                    _state.update { it.copy(screen = Screen.HOME, activeDeviceId = result.peerId, pairError = null) }
                    showToast("Verbunden mit ${result.name}")
                }
                is TransferService.ScanResult.Error -> {
                    _state.update { it.copy(pairError = result.message) }
                }
            }
        }
    }

    // ------------------------------------------------------------- chat

    fun onComposerChange(text: String) {
        _state.update { it.copy(composerText = text) }
    }

    fun sendMessage() {
        val text = _state.value.composerText.trim()
        val peerId = _state.value.activeDeviceId ?: return
        if (text.isEmpty()) return
        val isLink = Regex("^https?://").containsMatchIn(text) || Regex("^[\\w.-]+\\.[a-z]{2,}/").containsMatchIn(text)
        val body = if (isLink) {
            val url = if (Regex("^https?://").containsMatchIn(text)) text else "https://$text"
            val domain = text.replace(Regex("^https?://"), "").substringBefore('/')
            MessageBody(kind = "link", title = "Geteilter Link", domain = domain, url = url, time = now(), msgId = IdGen.next())
        } else {
            MessageBody(kind = "text", text = text, time = now(), msgId = IdGen.next())
        }
        val svc = service ?: return
        val result = svc.sendMessage(peerId, body)
        val entry = ChatMessageUi(
            id = "m-" + result.id, peerId = peerId, dir = "out", kind = body.kind,
            text = body.text, title = body.title, domain = body.domain, url = body.url,
            time = body.time, pending = result.queued,
        )
        _state.update { it.copy(messages = it.messages + entry, composerText = "") }
        if (result.queued) showToast("Wird gesendet, sobald das Gerät im Netzwerk ist")
    }

    fun sendFile(uri: Uri) {
        val peerId = _state.value.activeDeviceId ?: return
        val svc = service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val bytes = FileIo.readBytes(app, uri) ?: return@launch
            val (name, _) = FileIo.queryMeta(app, uri)
            val mime = FileIo.mimeType(app, uri)
            val result = svc.sendFile(peerId, name, mime, bytes)
            val isImage = mime.startsWith("image/")
            val entry = ChatMessageUi(
                id = "file-" + result.id, peerId = peerId, dir = "out",
                kind = if (isImage) "images" else "file",
                name = name, size = FileIo.fmtBytes(bytes.size.toLong()), ext = FileIo.extOf(name),
                time = now(), pending = result.queued, downloadUri = uri,
            )
            _state.update { it.copy(messages = it.messages + entry) }
            if (result.queued) showToast("Wird gesendet, sobald das Gerät im Netzwerk ist")
        }
    }

    fun acceptIncomingFile() {
        val f = _state.value.incomingFile ?: return
        service?.resolveIncomingFile(f.transferId, IncomingDecision.ACCEPT)
        _state.update { it.copy(incomingFile = null) }
    }

    fun rejectIncomingFile() {
        val f = _state.value.incomingFile ?: return
        service?.resolveIncomingFile(f.transferId, IncomingDecision.REJECT)
        _state.update { it.copy(incomingFile = null) }
    }

    fun cancelTransfer(transferId: String) {
        val t = _state.value.transfers.firstOrNull { it.id == transferId } ?: return
        service?.cancelTransfer(t.peerId, transferId)
    }

    // --------------------------------------------------------- settings

    fun setMyDeviceName(name: String) = viewModelScope.launch { peerStore.setMyDeviceName(name) }
    fun setAutoAccept(v: Boolean) = viewModelScope.launch { peerStore.setAutoAccept(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { peerStore.setNotifications(v) }
    fun setDarkTheme(v: Boolean) = viewModelScope.launch { peerStore.setDarkTheme(v) }

    fun startRename(id: String) {
        _state.update { it.copy(renamingDeviceId = id) }
    }

    fun confirmRename(id: String, name: String) {
        viewModelScope.launch {
            service?.renamePeer(id, name)
            _state.update { it.copy(renamingDeviceId = null) }
        }
    }

    fun cancelRename() {
        _state.update { it.copy(renamingDeviceId = null) }
    }

    fun forgetDevice(id: String) {
        viewModelScope.launch { service?.forgetPeer(id) }
    }

    override fun onCleared() {
        if (bound) {
            runCatching { getApplication<Application>().unbindService(connection) }
        }
        super.onCleared()
    }
}
