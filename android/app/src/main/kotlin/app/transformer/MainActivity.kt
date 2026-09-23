package app.transformer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.transformer.ui.screens.ChatScreen
import app.transformer.ui.screens.PairingScreen
import app.transformer.ui.screens.SettingsScreen
import app.transformer.ui.screens.TransferBottomBar
import app.transformer.ui.screens.TransfersScreen
import app.transformer.ui.theme.TransformerTheme
import app.transformer.viewmodel.Screen
import app.transformer.viewmodel.TransformerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: TransformerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            TransformerTheme(darkTheme = state.settings.darkTheme) {
                RequestStartupPermissions()
                Scaffold(
                    bottomBar = {
                        if (state.screen != Screen.PAIRING) {
                            TransferBottomBar(current = state.screen, onSelect = viewModel::goTo)
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (state.screen) {
                            Screen.PAIRING -> PairingScreen(viewModel = viewModel, state = state, padding = padding)
                            Screen.HOME -> ChatScreen(viewModel = viewModel, state = state, padding = padding)
                            Screen.TRANSFERS -> TransfersScreen(viewModel = viewModel, state = state, padding = padding)
                            Screen.SETTINGS -> SettingsScreen(viewModel = viewModel, state = state, padding = padding)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestStartupPermissions() {
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
