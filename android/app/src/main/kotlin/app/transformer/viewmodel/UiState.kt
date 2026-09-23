package app.transformer.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import app.transformer.data.AppSettings

data class ChatMessageUi(
    val id: String,
    val peerId: String,
    val dir: String, // "in" | "out"
    val kind: String, // "text" | "link" | "file" | "images"
    val text: String? = null,
    val title: String? = null,
    val domain: String? = null,
    val url: String? = null,
    val name: String? = null,
    val size: String? = null,
    val ext: String? = null,
    val time: String,
    val pending: Boolean = false,
    val downloadUri: Uri? = null,
)

data class TransferUi(
    val id: String,
    val peerId: String,
    val name: String,
    val size: String,
    val ext: String,
    val dir: String, // "send" | "receive"
    val status: String, // "active" | "waiting" | "done" | "failed"
    val progress: Int,
    val reason: String? = null,
    val downloadUri: Uri? = null,
)

data class DeviceUi(val id: String, val name: String, val online: Boolean)

enum class Screen { PAIRING, HOME, TRANSFERS, SETTINGS }
enum class PairMode { SHOW, SCAN }

data class UiState(
    val screen: Screen = Screen.PAIRING,
    val devices: List<DeviceUi> = emptyList(),
    val activeDeviceId: String? = null,
    val messages: List<ChatMessageUi> = emptyList(),
    val transfers: List<TransferUi> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val composerText: String = "",
    val pairMode: PairMode = PairMode.SHOW,
    val qrBitmap: Bitmap? = null,
    val qrPending: Boolean = false,
    val pairError: String? = null,
    val incomingFile: IncomingFileUi? = null,
    val renamingDeviceId: String? = null,
    val toast: String? = null,
) {
    val isConnectedToActive: Boolean
        get() = devices.firstOrNull { it.id == activeDeviceId }?.online == true
}

data class IncomingFileUi(val peerId: String, val peerName: String, val transferId: String, val name: String, val size: String)
