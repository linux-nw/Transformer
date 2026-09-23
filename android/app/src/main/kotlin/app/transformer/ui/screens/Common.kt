package app.transformer.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transformer.viewmodel.Screen

@Composable
fun TransferBottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = current == Screen.HOME,
            onClick = { onSelect(Screen.HOME) },
            icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
            label = { Text("Chat") },
        )
        NavigationBarItem(
            selected = current == Screen.TRANSFERS,
            onClick = { onSelect(Screen.TRANSFERS) },
            icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
            label = { Text("Übertragungen") },
        )
        NavigationBarItem(
            selected = current == Screen.SETTINGS,
            onClick = { onSelect(Screen.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Einstellungen") },
        )
    }
}

/** Bottom-anchored toast that auto-dismisses, mirroring the web prototype's toast list (single-at-a-time here). */
@Composable
fun ToastHost(text: String?, onConsumed: () -> Unit) {
    if (text == null) return
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(3200)
        onConsumed()
    }
    Snackbar(
        modifier = Modifier.padding(16.dp),
        containerColor = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Text(text)
    }
}
