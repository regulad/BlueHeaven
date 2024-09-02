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
    const val BT_ONBOARDING = "bluetooth"
    const val NOTO_ONBOARDING = "notifications"
    const val UNSUPPORTED = "unsupported"
    const val DEBUG = "debug"
}

@Composable
fun BlueHeavenNavHost(modifier: Modifier = Modifier, blueHeavenViewModel: BlueHeavenViewModel) {
    val navController = rememberNavController()

    NavHost(navController, startDestination = BlueHeavenRoute.DEBUG, modifier = modifier) {
        composable(route = BlueHeavenRoute.BT_ONBOARDING) {
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

