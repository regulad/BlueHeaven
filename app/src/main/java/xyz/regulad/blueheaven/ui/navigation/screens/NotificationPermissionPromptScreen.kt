package xyz.regulad.blueheaven.ui.navigation.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
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
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import xyz.regulad.blueheaven.util.launchAppInfoSettings
import xyz.regulad.blueheaven.util.navigateWithoutHistory
import xyz.regulad.blueheaven.util.showDialog

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NotificationPermissionPromptScreen(
    navController: NavController,
    nextScreen: String
) {
    val context = LocalContext.current

    val permissionState = rememberPermissionState(
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    LaunchedEffect(permissionState.status.isGranted) {
        if (permissionState.status.isGranted) {
            // Notification permission granted, navigate to the next screen
            navController.navigateWithoutHistory(nextScreen)
        }
    }

    LaunchedEffect(permissionState.status.shouldShowRationale) {
        // just go to the next screen; don't bother the user with a dialog
        if (permissionState.status.shouldShowRationale) {
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
            imageVector = Icons.Filled.Notifications,
            contentDescription = "Notifications Icon",
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enable Notifications",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "BlueHeaven can send notifications to keep you in the loop on received messages and alerts. In addition, BlueHeaven will be permitted to run in the background. Enabling is strongly recommended, but not required.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (permissionState.status.shouldShowRationale) {
                    context.showDialog(
                        title = "BlueHeaven",
                        message = "You'll need to enable the permission in your app's settings, since you already denied it once.",
                        positiveButtonText = "Go to Settings",
                        onPositiveClick = {
                            context.launchAppInfoSettings()
                        },
                        negativeButtonText = "Not Now",
                        onNegativeClick = {
                            navController.navigateWithoutHistory(nextScreen)
                        }
                    )
                } else {
                    permissionState.launchPermissionRequest()
                }
            }
        ) {
            Text("Enable Notifications")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                navController.navigateWithoutHistory(nextScreen)
            },
        ) {
            Text("Skip")
        }
    }
}
