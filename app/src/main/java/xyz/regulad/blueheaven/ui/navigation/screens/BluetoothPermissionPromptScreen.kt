package xyz.regulad.blueheaven.ui.navigation.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
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
import xyz.regulad.blueheaven.ui.navigation.BlueHeavenRoute
import xyz.regulad.blueheaven.util.showDialog

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BluetoothPermissionPromptScreen(
    blueHeavenViewModel: BlueHeavenViewModel,
    navController: NavController,
    nextScreen: String
) {
    val context = LocalContext.current

    val permissionState =
        rememberMultiplePermissionsState(
            permissions = RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS.toList(),
            onPermissionsResult = { permissions ->
                if (permissions.all { it.value }) {
                    // send a notification saying expect the Bluetooth Share to crash
                    val isSamsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
                    if (isSamsung) {
                        context.showDialog(
                            title = "BlueHeaven",
                            message = "You may see a notification saying \"Bluetooth Share has stopped\". This is normal and expected while BlueHeaven is active. Please ignore it.",
                        )
                    }
                }
            }
        )

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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Please grant the permissions to continue.",
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

        Spacer(modifier = Modifier.height(8.dp))

        val activity = (LocalContext.current as? Activity)

        OutlinedButton (
            onClick = {
                activity?.finish()
            },
        ) {
            Text("Close App")
        }
    }
}
