package app.transformer.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.transformer.viewmodel.ChatMessageUi
import app.transformer.viewmodel.TransformerViewModel
import app.transformer.viewmodel.UiState

@Composable
fun ChatScreen(viewModel: TransformerViewModel, state: UiState, padding: PaddingValues) {
    val messages = state.messages.filter { it.peerId == state.activeDeviceId }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.sendFile(it) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(
            title = {
                DeviceDropdown(state = state, onSelect = viewModel::selectDevice)
            },
            actions = {
                val tagColor = if (state.isConnectedToActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                Text(
                    if (state.isConnectedToActive) "Verbunden" else "Getrennt",
                    color = tagColor,
                    modifier = Modifier.padding(end = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Noch keine Nachrichten", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(messages) { _, m ->
                    MessageBubble(m, onOpenLink = { url -> uriHandler.openUri(url) }, onOpenFile = { uri ->
                        val mime = context.contentResolver.getType(uri) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { filePicker.launch("*/*") }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Anhang")
            }
            OutlinedTextField(
                value = state.composerText,
                onValueChange = viewModel::onComposerChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nachricht oder Link…") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = viewModel::sendMessage, enabled = state.composerText.isNotBlank() && state.activeDeviceId != null) {
                Icon(Icons.Filled.Send, contentDescription = "Senden")
            }
        }
    }

    state.incomingFile?.let { f ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Eingehende Übertragung") },
            text = { Text("${f.name} von ${f.peerName} · ${f.size}") },
            confirmButton = { TextButton(onClick = viewModel::acceptIncomingFile) { Text("Annehmen") } },
            dismissButton = { TextButton(onClick = viewModel::rejectIncomingFile) { Text("Ablehnen") } },
        )
    }

    ToastHost(text = state.toast, onConsumed = viewModel::consumeToast)
}

@Composable
private fun DeviceDropdown(state: UiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val activeName = state.devices.firstOrNull { it.id == state.activeDeviceId }?.name ?: "Kein Gerät"
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(activeName, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.name + if (d.online) " ●" else "") },
                    onClick = { onSelect(d.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessageUi, onOpenLink: (String) -> Unit, onOpenFile: (android.net.Uri) -> Unit) {
    val isOut = m.dir == "out"
    val bg = if (isOut && m.kind == "text") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (isOut && m.kind == "text") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bg),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                when (m.kind) {
                    "text" -> Text(m.text ?: "", color = fg)
                    "link" -> Column(modifier = Modifier.clickable { m.url?.let(onOpenLink) }) {
                        Text(m.title ?: "Link", fontWeight = FontWeight.Bold, color = fg)
                        Text(m.domain ?: "", style = MaterialTheme.typography.bodySmall, color = fg)
                    }
                    "file", "images" -> Column(
                        modifier = Modifier.clickable { m.downloadUri?.let(onOpenFile) },
                    ) {
                        Text(m.name ?: "Datei", fontWeight = FontWeight.Bold, color = fg)
                        Text("${m.size ?: ""} · ${m.ext ?: ""}", style = MaterialTheme.typography.bodySmall, color = fg)
                    }
                }
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    if (m.pending) Text("⏳ ", style = MaterialTheme.typography.labelSmall, color = fg)
                    Text(m.time, style = MaterialTheme.typography.labelSmall, color = fg)
                }
            }
        }
    }
}
