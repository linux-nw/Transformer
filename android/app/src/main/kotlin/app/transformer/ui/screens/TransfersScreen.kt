package app.transformer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transformer.viewmodel.TransferUi
import app.transformer.viewmodel.TransformerViewModel
import app.transformer.viewmodel.UiState

@Composable
fun TransfersScreen(viewModel: TransformerViewModel, state: UiState, padding: PaddingValues) {
    val transfers = state.transfers
    val active = transfers.filter { it.status == "active" }
    val waiting = transfers.filter { it.status == "waiting" }
    val failed = transfers.filter { it.status == "failed" }
    val done = transfers.filter { it.status == "done" }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(title = { Text("Übertragungen") })

        if (transfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch keine Übertragungen", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (active.isNotEmpty()) {
                    item { SectionHeader("Läuft") }
                    items(active, key = { it.id }) { TransferRow(it, onCancel = { viewModel.cancelTransfer(it.id) }) }
                }
                if (waiting.isNotEmpty()) {
                    item { SectionHeader("Wartend") }
                    items(waiting, key = { it.id }) { TransferRow(it, onCancel = { viewModel.cancelTransfer(it.id) }) }
                }
                if (failed.isNotEmpty()) {
                    item { SectionHeader("Fehlgeschlagen") }
                    items(failed, key = { it.id }) { TransferRow(it, onCancel = null) }
                }
                if (done.isNotEmpty()) {
                    item { SectionHeader("Fertig") }
                    items(done, key = { it.id }) { TransferRow(it, onCancel = null) }
                }
            }
        }
    }

    ToastHost(text = state.toast, onConsumed = viewModel::consumeToast)
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun TransferRow(t: TransferUi, onCancel: (() -> Unit)?) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall)
                    val sub = if (t.status == "failed") (t.reason ?: "Fehlgeschlagen") else "${t.size} · ${t.ext}"
                    Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                if (onCancel != null) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Abbrechen") }
                }
            }
            if (t.status == "active") {
                LinearProgressIndicator(
                    progress = { t.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                )
            }
        }
    }
}
