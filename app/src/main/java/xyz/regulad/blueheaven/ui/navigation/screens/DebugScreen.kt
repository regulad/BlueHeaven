package xyz.regulad.blueheaven.ui.navigation.screens

import NodeGraphVisualization
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import xyz.regulad.blueheaven.BlueHeavenViewModel
import xyz.regulad.blueheaven.network.NetworkConstants.canOpenBluetooth
import xyz.regulad.blueheaven.network.NetworkConstants.hasBluetoothHardwareSupport
import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex
import xyz.regulad.blueheaven.ui.components.LogcatViewer
import xyz.regulad.blueheaven.ui.components.MaxWidthContainer
import xyz.regulad.blueheaven.ui.components.PublicKeyQRCode
import xyz.regulad.blueheaven.ui.navigation.BlueHeavenRoute

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
fun DebugScreen(
    blueHeavenViewModel: BlueHeavenViewModel,
    navController: NavController
) {
    // screen on for debug
    KeepScreenOn()

    val context = LocalView.current.context

    LaunchedEffect(Unit) {
        val hasHardwareSupport = hasBluetoothHardwareSupport(context)

        if (!hasHardwareSupport) {
            navController.navigate(BlueHeavenRoute.UNSUPPORTED)
            return@LaunchedEffect
        }

        val hasBluetoothPermission = canOpenBluetooth(context)
        if (!hasBluetoothPermission) {
            navController.navigate(BlueHeavenRoute.BT_ONBOARDING)
        }
    }

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

    val updateNodes = fun() {
        reachableNodes = blueHeavenViewModel.getRouter()?.getReachableNodeIDs() ?: emptySet()
        directConnections = blueHeavenViewModel.getRouter()?.getDirectlyConnectedNodeIDs() ?: emptySet()
    }

    DisposableEffect(blueHeavenViewModel.getFrontend()) {
        val frontend = blueHeavenViewModel.getFrontend()
            ?: return@DisposableEffect onDispose {
                // nothing to do; frontend doesn't exist yet
            }

        frontend.addTopologyChangeListener(updateNodes)

        onDispose {
            frontend.removeTopologyChangeListener(updateNodes)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            updateNodes() // can sometimes desync
            delay(1000)
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
            Text(
                text = "This node's public key:",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // making it encoded here helps us save rerenders because the encoded can be compared
            PublicKeyQRCode(publicKeyParameters = publicKey.encoded)

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "Topology",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        val edges = mutableSetOf<Pair<UInt, UInt>>().apply {
            val knownExternalRoutes = blueHeavenViewModel.getRouter()?.getRoutes() ?: emptyMap()
            knownExternalRoutes.forEach { (from, to) ->
                to.forEach {
                    add(from to it)
                }
            }

            val knownDirectConnections = blueHeavenViewModel.getRouter()?.getDirectlyConnectedNodeIDs() ?: emptySet()
            knownDirectConnections.forEach {
                add(blueHeavenViewModel.getPreferences()?.getNodeId()!! to it)
            }
        }.toSet()

        MaxWidthContainer(600.dp) {
            NodeGraphVisualization(
                center = blueHeavenViewModel.getPreferences()?.getNodeId() ?: 0u,
                innerRing = directConnections,
                outerRing = reachableNodes,
                edges = edges,
                modifier = Modifier
                    .aspectRatio(1F)
                    .fillMaxWidth()
                    .height(400.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

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
