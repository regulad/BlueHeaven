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
import xyz.regulad.blueheaven.BlueHeavenServiceViewModel
import xyz.regulad.blueheaven.network.NetworkConstants.RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS
import xyz.regulad.blueheaven.network.NetworkConstants.canOpenBluetooth
import xyz.regulad.blueheaven.util.launchAppInfoSettings
import xyz.regulad.blueheaven.util.navigateWithoutHistory
import xyz.regulad.blueheaven.util.showDialog

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BluetoothPermissionPromptScreen(
    blueHeavenServiceViewModel: BlueHeavenServiceViewModel,
    navController: NavController,
    nextScreen: String
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val permissionState =
        rememberMultiplePermissionsState(
            permissions = RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS.toList(),
            onPermissionsResult = { permissions ->
                if (permissions.all { it.value }) {
                    // send a notification saying expect the Bluetooth Share to crash
                    context.showDialog(
                        title = "BlueHeaven",
                        message = "You may see a notification saying \"Bluetooth Share has stopped\". This is expected while BlueHeaven is active. Please ignore it.",
                    )
                }
            }
        )

    LaunchedEffect(permissionState.allPermissionsGranted) {
        val hasBluetoothPermission = canOpenBluetooth(context)
        if (hasBluetoothPermission) {
            // if the user has already granted permission, the service will be null and will start itself
            // otherwise, we will start it here
            blueHeavenServiceViewModel.getFrontend()?.startBluetooth()

            navController.navigateWithoutHistory(nextScreen)
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
            text = "BlueHeaven needs Bluetooth permissions to send & receive messages. On older Android versions, you may be prompted for your location as well.",
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
                // if the user denied the permissions, we can't show the dialog and need to direct them to go to settings
                if (permissionState.shouldShowRationale) {
                    context.showDialog(
                        title = "BlueHeaven",
                        message = "Please go to settings and grant the required permissions to continue. If you wouldn't like to do this, you can close the app.",
                        positiveButtonText = "Go",
                        onPositiveClick = {
                            // go to the settings
                            context.launchAppInfoSettings()
                        },
                        negativeButtonText = "Close App",
                        onNegativeClick = {
                            activity?.finish()
                        }
                    )
                } else {
                    permissionState.launchMultiplePermissionRequest()
                }
            }
        ) {
            Text("Grant Permissions")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton (
            onClick = {
                activity?.finish()
            },
        ) {
            Text("Close App")
        }
    }
}
