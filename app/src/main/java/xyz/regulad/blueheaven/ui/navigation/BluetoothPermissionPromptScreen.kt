package xyz.regulad.blueheaven.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import xyz.regulad.blueheaven.BlueHeavenViewModel
import xyz.regulad.blueheaven.network.NetworkConstants.RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS
import xyz.regulad.blueheaven.network.NetworkConstants.canOpenBluetooth
import xyz.regulad.blueheaven.network.NetworkConstants.hasBluetoothHardwareSupport

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BluetoothPermissionPromptScreen(
    blueHeavenViewModel: BlueHeavenViewModel,
    navController: NavController,
    nextScreen: String
) {
    val context = LocalContext.current

    val permissionState =
        rememberMultiplePermissionsState(permissions = RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS.toList())

    LaunchedEffect(permissionState.allPermissionsGranted) {
        val hasHardwareSupport = hasBluetoothHardwareSupport(context)

        if (!hasHardwareSupport) {
            navController.navigate(BlueHeavenRoute.UNSUPPORTED)
            return@LaunchedEffect
        }

        val hasBluetoothPermission = canOpenBluetooth(context)
        if (hasBluetoothPermission) {
            // if the user has already granted permission, the service will be null and will start itself
            // otherwise, we will start it here
            blueHeavenViewModel.getFrontend()?.startBluetooth()

            navController.navigate(nextScreen)
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = "Bluetooth Icon",
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Bluetooth Permissions Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "BlueHeaven needs Bluetooth and Location permissions to send & receive messages.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                permissionState.launchMultiplePermissionRequest()
            }
        ) {
            Text("Grant Permissions")
        }
    }
}
