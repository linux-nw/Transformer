package app.transformer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transformer.viewmodel.DeviceUi
import app.transformer.viewmodel.TransformerViewModel
import app.transformer.viewmodel.UiState

@Composable
fun SettingsScreen(viewModel: TransformerViewModel, state: UiState, padding: PaddingValues) {
    var forgetTarget by remember { mutableStateOf<DeviceUi?>(null) }
    var nameField by remember(state.settings.myDeviceName) { mutableStateOf(state.settings.myDeviceName) }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(title = { Text("Einstellungen") })

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Dieses Gerät", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text("Gerätename (sichtbar für gekoppelte Geräte)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (nameField != state.settings.myDeviceName) {
                            TextButton(onClick = { viewModel.setMyDeviceName(nameField) }) { Text("Speichern") }
                        }
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Automatisch annehmen", modifier = Modifier.weight(1f))
                    Switch(checked = state.settings.autoAccept, onCheckedChange = viewModel::setAutoAccept)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Geräte", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(top = 14.dp))
                    TextButton(onClick = viewModel::addDevice) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Hinzufügen")
                    }
                }
            }

            if (state.devices.isEmpty()) {
                item { Text("Noch kein Gerät gekoppelt.", style = MaterialTheme.typography.bodySmall) }
            }
            items(state.devices, key = { it.id }) { d ->
                DeviceRow(
                    device = d,
                    isRenaming = state.renamingDeviceId == d.id,
                    onRename = viewModel::startRename,
                    onConfirmRename = viewModel::confirmRename,
                    onCancelRename = viewModel::cancelRename,
                    onReconnect = viewModel::reconnectDevice,
                    onForget = { forgetTarget = d },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Benachrichtigungen", modifier = Modifier.weight(1f))
                    Switch(checked = state.settings.notifications, onCheckedChange = viewModel::setNotifications)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Dunkles Design", modifier = Modifier.weight(1f))
                    Switch(checked = state.settings.darkTheme, onCheckedChange = viewModel::setDarkTheme)
                }
                Text(
                    "Ende-zu-Ende-verschlüsselt · nur im lokalen Netzwerk",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                )
            }
        }
    }

    forgetTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Gerät entkoppeln") },
            text = { Text("\"${d.name}\" wird entkoppelt, der Schlüssel gelöscht. Für eine neue Verbindung ist erneutes Pairing nötig.") },
            confirmButton = {
                TextButton(onClick = { viewModel.forgetDevice(d.id); forgetTarget = null }) { Text("Entkoppeln") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Abbrechen") } },
        )
    }

    ToastHost(text = state.toast, onConsumed = viewModel::consumeToast)
}

@Composable
private fun DeviceRow(
    device: DeviceUi,
    isRenaming: Boolean,
    onRename: (String) -> Unit,
    onConfirmRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    onReconnect: (String) -> Unit,
    onForget: () -> Unit,
) {
    if (isRenaming) {
        var value by remember { mutableStateOf(device.name) }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.weight(1f))
            TextButton(onClick = { onConfirmRename(device.id, value) }) { Text("OK") }
            TextButton(onClick = onCancelRename) { Text("Abbrechen") }
        }
        return
    }
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(if (device.online) "Online · verbunden" else "Nicht im Netzwerk") },
        leadingContent = {
            val color = if (device.online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, androidx.compose.foundation.shape.CircleShape),
            )
        },
        trailingContent = {
            Row {
                if (!device.online) {
                    IconButton(onClick = { onReconnect(device.id) }) { Icon(Icons.Filled.Refresh, contentDescription = "Neu verbinden") }
                }
                IconButton(onClick = { onRename(device.id) }) { Icon(Icons.Filled.Edit, contentDescription = "Umbenennen") }
                IconButton(onClick = onForget) { Icon(Icons.Filled.Delete, contentDescription = "Entkoppeln") }
            }
        },
    )
}
