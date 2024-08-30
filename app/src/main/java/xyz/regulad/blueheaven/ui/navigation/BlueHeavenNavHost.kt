package xyz.regulad.blueheaven.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import xyz.regulad.blueheaven.GlobalStateViewModel

object BlueHeavenRoute {
    const val ONBOARDING = "bluetooth"
    const val UNSUPPORTED = "unsupported"
    const val INFO = "info"
}

@Composable
fun BlueHeavenNavHost(modifier: Modifier = Modifier, globalStateViewModel: GlobalStateViewModel) {
    val navController = rememberNavController()

    val globalState = globalStateViewModel.state.value

    NavHost(navController, startDestination = BlueHeavenRoute.ONBOARDING, modifier = modifier) {
        composable(route = BlueHeavenRoute.ONBOARDING) {
            BluetoothPermissionPromptScreen(
                globalStateViewModel = globalStateViewModel,
                navController = navController,
                nextScreen = BlueHeavenRoute.INFO
            )
        }
        composable(route = BlueHeavenRoute.UNSUPPORTED) {
            DeviceUnsupportedScreen()
        }
        composable(route = BlueHeavenRoute.INFO) {
            InfoScreen(globalState = globalState)
        }
        // Add more composable destinations here as needed
    }
}

