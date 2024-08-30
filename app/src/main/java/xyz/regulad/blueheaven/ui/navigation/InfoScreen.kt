package xyz.regulad.blueheaven.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.regulad.blueheaven.GlobalState
import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex

@Composable
fun InfoScreen(globalState: GlobalState) {
    var reachableNodes by remember {
        mutableStateOf(
            globalState.networkAdapter?.backend?.getReachableNodes() ?: emptySet()
        )
    }
    var directConnections by remember {
        mutableStateOf(
            globalState.networkAdapter?.backend?.getDirectConnections() ?: emptySet()
        )
    }

    DisposableEffect(Unit) {
        val listener = fun() {
            reachableNodes = globalState.networkAdapter?.backend?.getReachableNodes() ?: emptySet()
            directConnections = globalState.networkAdapter?.backend?.getDirectConnections() ?: emptySet()
        }

        globalState.networkAdapter?.addTopologyChangeListener(listener)

        onDispose {
            globalState.networkAdapter?.removeTopologyChangeListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Node Information",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Node ID: ${globalState.preferences?.getNodeId()?.toStardardLengthHex()}",
        )

        Spacer(modifier = Modifier.height(8.dp))

//        if (globalState.preferences?.getPublicKey() != null) {
//            Spacer(modifier = Modifier.height(8.dp))
//
//            PublicKeyQRCode(publicKeyParameters = globalState.preferences.getPublicKey()!!)
//        }

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
