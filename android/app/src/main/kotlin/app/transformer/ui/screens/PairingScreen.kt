package app.transformer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import app.transformer.viewmodel.DeviceUi
import app.transformer.viewmodel.PairMode
import app.transformer.viewmodel.TransformerViewModel
import app.transformer.viewmodel.UiState
import app.transformer.camera.QrScannerView

@OptIn(ExperimentalGetImage::class)
@Composable
fun PairingScreen(viewModel: TransformerViewModel, state: UiState, padding: PaddingValues) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    // Only kick off a fresh offer on a genuine first visit (state.pairError
    // aside, nobody has called startShowOffer yet) — reconnectDevice()/
    // addDevice() already start their own offer with the right reuseId
    // before navigating here, and re-running this would clobber that with
    // an unwanted brand-new pairing.
    LaunchedEffect(Unit) {
        if (!state.qrPending && state.qrBitmap == null) viewModel.startShowOffer(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(18.dp),
    ) {
        Text("Gerät verbinden", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Nur im selben WLAN, Ende-zu-Ende-verschlüsselt, kein Server. Ein Gerät zeigt den Code, das andere scannt ihn.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.pairMode == PairMode.SHOW,
                onClick = { viewModel.setPairMode(PairMode.SHOW) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("QR-Code zeigen") }
            SegmentedButton(
                selected = state.pairMode == PairMode.SCAN,
                onClick = {
                    viewModel.setPairMode(PairMode.SCAN)
                    if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("QR-Code scannen") }
        }

        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (state.pairMode) {
                PairMode.SHOW -> {
                    if (state.qrPending || state.qrBitmap == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(
                            bitmap = state.qrBitmap.asImageBitmap(),
                            contentDescription = "QR-Code",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                                .background(androidx.compose.ui.graphics.Color.White),
                        )
                    }
                }
                PairMode.SCAN -> {
                    if (hasCameraPermission) {
                        QrScannerView(modifier = Modifier.fillMaxSize()) { text ->
                            viewModel.onQrScanned(text)
                        }
                    } else {
                        Text("Kamera-Zugriff nötig zum Scannen", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Text(
            when {
                state.pairMode == PairMode.SHOW -> "Lass das andere Gerät diesen Code scannen — die Verbindung steht, sobald gescannt wurde."
                else -> "Richte die Kamera auf den Code des anderen Geräts."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )

        state.pairError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        val knownOffline = state.devices.filterNot { it.online }
        if (knownOffline.isNotEmpty()) {
            Text("Bereits gekoppelt", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            LazyColumn {
                items(knownOffline) { device: DeviceUi ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text("Erneut verbinden (neuer Code nötig)") },
                        modifier = Modifier.clickable { viewModel.reconnectDevice(device.id) },
                    )
                }
            }
        }
    }
}
