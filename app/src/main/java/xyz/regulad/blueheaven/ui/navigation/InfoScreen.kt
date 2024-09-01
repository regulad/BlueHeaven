package xyz.regulad.blueheaven.ui.navigation

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.regulad.blueheaven.BlueHeavenViewModel
import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex
import xyz.regulad.blueheaven.ui.components.LogcatViewer
import xyz.regulad.blueheaven.ui.components.PublicKeyQRCode

@Composable
fun KeepScreenOn() {
    val currentView = LocalView.current
    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        onDispose {
            currentView.keepScreenOn = false
        }
    }
}

@Composable
fun InfoScreen(blueHeavenViewModel: BlueHeavenViewModel) {
    // screen on for debug
    KeepScreenOn()

    var reachableNodes by remember {
        mutableStateOf(
            blueHeavenViewModel.getRouter()?.getReachableNodeIDs() ?: emptySet()
        )
    }
    var directConnections by remember {
        mutableStateOf(
            blueHeavenViewModel.getRouter()?.getDirectlyConnectedNodeIDs() ?: emptySet()
        )
    }

    DisposableEffect(blueHeavenViewModel.getFrontend()) {
        val frontend = blueHeavenViewModel.getFrontend()
            ?: return@DisposableEffect onDispose {
                // nothing to do; frontend doesn't exist yet
            }

        val listener = fun() {
            reachableNodes = blueHeavenViewModel.getRouter()?.getReachableNodeIDs() ?: emptySet()
            directConnections = blueHeavenViewModel.getRouter()?.getDirectlyConnectedNodeIDs() ?: emptySet()
        }

        frontend.addTopologyChangeListener(listener)

        onDispose {
            frontend.removeTopologyChangeListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "BlueHeaven Debug",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LogcatViewer(rows = 20, columns = 80)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Network Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // router
        var isServerRunning by remember {
            mutableStateOf(blueHeavenViewModel.getRouter()?.isServerRunning() ?: true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Server",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = isServerRunning,
                onCheckedChange = {
                    if (it) {
                        blueHeavenViewModel.getRouter()?.start()
                    } else {
                        blueHeavenViewModel.getRouter()?.close()
                    }
                    isServerRunning = blueHeavenViewModel.getRouter()?.isServerRunning() ?: true
                }
            )
        }

        // advertiser
        var isAdvertising by remember {
            mutableStateOf(blueHeavenViewModel.getAdvertiser()?.isAdvertising() ?: true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Advertiser",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = isAdvertising,
                onCheckedChange = {
                    if (it) {
                        blueHeavenViewModel.getAdvertiser()?.start()
                    } else {
                        blueHeavenViewModel.getAdvertiser()?.close()
                    }
                    isAdvertising = blueHeavenViewModel.getAdvertiser()?.isAdvertising() ?: true
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // scanner
        var isScanning by remember {
            mutableStateOf(blueHeavenViewModel.getScanner()?.isScanning() ?: true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Scanner",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = isScanning,
                onCheckedChange = {
                    if (it) {
                        blueHeavenViewModel.getScanner()?.start()
                    } else {
                        blueHeavenViewModel.getScanner()?.close()
                    }
                    isScanning = blueHeavenViewModel.getScanner()?.isScanning() ?: true
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Node Information",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Node ID: ${blueHeavenViewModel.getPreferences()?.getNodeId()?.toStardardLengthHex() ?: "Loading..."}",
        )

        Spacer(modifier = Modifier.height(8.dp))

        val publicKey = blueHeavenViewModel.getPreferences()?.getPublicKey()

        if (publicKey != null) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Public Key",
                style = MaterialTheme.typography.bodyMedium
            )

            PublicKeyQRCode(publicKeyParameters = publicKey)
        }

        Text(
            text = "Reachable Nodes",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column {
            if (reachableNodes.isEmpty()) {
                Text(
                    text = "No reachable nodes",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // don't use a lazycolumn because the parent view is scrollable
                reachableNodes.forEach { node ->
                    Text(
                        text = node.toStardardLengthHex(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Direct Connections",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column {
            if (directConnections.isEmpty()) {
                Text(
                    text = "No direct connections",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // don't use a lazycolumn because the parent view is scrollable
                directConnections.forEach { node ->
                    Text(
                        text = node.toStardardLengthHex(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
