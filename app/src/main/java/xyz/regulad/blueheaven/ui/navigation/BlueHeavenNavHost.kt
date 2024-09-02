package xyz.regulad.blueheaven.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import xyz.regulad.blueheaven.BlueHeavenViewModel
import xyz.regulad.blueheaven.ui.navigation.screens.BluetoothPermissionPromptScreen
import xyz.regulad.blueheaven.ui.navigation.screens.DeviceUnsupportedScreen
import xyz.regulad.blueheaven.ui.navigation.screens.DebugScreen

object BlueHeavenRoute {
    const val ONBOARDING = "bluetooth"
    const val NOTIFICATIONS = "notifications"
    const val UNSUPPORTED = "unsupported"
    const val DEBUG = "debug"
}

@Composable
fun BlueHeavenNavHost(modifier: Modifier = Modifier, blueHeavenViewModel: BlueHeavenViewModel) {
    val navController = rememberNavController()

    NavHost(navController, startDestination = BlueHeavenRoute.ONBOARDING, modifier = modifier) {
        composable(route = BlueHeavenRoute.ONBOARDING) {
            BluetoothPermissionPromptScreen(
                blueHeavenViewModel = blueHeavenViewModel,
                navController = navController,
                nextScreen = BlueHeavenRoute.DEBUG
            )
        }
        composable(route = BlueHeavenRoute.UNSUPPORTED) {
            DeviceUnsupportedScreen()
        }
        composable(route = BlueHeavenRoute.DEBUG) {
            DebugScreen(blueHeavenViewModel = blueHeavenViewModel)
        }
        // Add more composable destinations here as needed
    }
}

