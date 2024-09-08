package xyz.regulad.blueheaven.ui.navigation

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import xyz.regulad.blueheaven.BlueHeavenService.Companion.needToRequestNotifications
import xyz.regulad.blueheaven.BlueHeavenServiceViewModel
import xyz.regulad.blueheaven.network.NetworkConstants.canOpenBluetooth
import xyz.regulad.blueheaven.network.NetworkConstants.hasBluetoothHardwareSupport
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.ui.navigation.screens.*

object BlueHeavenRoute {
    const val BT_ONBOARDING = "bluetooth"
    const val NOTO_ONBOARDING = "notifications"
    const val SENTRY_ONBOARDING = "sentry"
    const val BH_ONBOARDING = "onboarding"
    const val UNSUPPORTED = "unsupported"
    const val DEBUG = "debug"
}

@Composable
fun BlueHeavenNavHost(
    modifier: Modifier = Modifier,
    blueHeavenServiceViewModel: BlueHeavenServiceViewModel,
    preferencesRepository: UserPreferencesRepository,
    database: BlueHeavenDatabase
) {
    val navController = rememberNavController()

    val context = LocalContext.current as Activity

    // bluetooth -> notification -> sentry -> onboarding -> finalScreen
    val finalDestination =
        BlueHeavenRoute.DEBUG // TODO: implement more screens and the ability to swap which screen you want to go to on boot

    val needToRequestRuntimePermissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    val needToRequestNotificationPermissions = needToRequestNotifications(context)

    val hasHardwareSupport = hasBluetoothHardwareSupport(context)
    val canOpenBluetooth = hasHardwareSupport && canOpenBluetooth(context)

    val needToAskSentry =
        preferencesRepository.getSentryPermissions() == UserPreferencesRepository.SentryPermissions.UNASKED
    val needToOnboardBH = !preferencesRepository.isKeyStoreInitalized()

    val secondFinalDestination = if (needToOnboardBH) {
        BlueHeavenRoute.BH_ONBOARDING
    } else {
        finalDestination
    }

    val thirdFinalDestination = if (needToAskSentry) {
        BlueHeavenRoute.SENTRY_ONBOARDING
    } else {
        secondFinalDestination
    }

    val fourthFinalDestination = if (needToRequestNotificationPermissions) {
        BlueHeavenRoute.NOTO_ONBOARDING
    } else {
        thirdFinalDestination
    }

    val startDestination = when {
        !hasHardwareSupport -> BlueHeavenRoute.UNSUPPORTED
        (needToRequestRuntimePermissions && !canOpenBluetooth) -> BlueHeavenRoute.BT_ONBOARDING
        else -> fourthFinalDestination
    }

    NavHost(navController, startDestination = startDestination, modifier = modifier) {
        // === ONBOARDING SCREENS ===

        // always include; if we don't need to request runtime permissions, this screen will just be skipped
        composable(route = BlueHeavenRoute.UNSUPPORTED) {
            DeviceUnsupportedScreen()
        }

        // always include; if we don't need to request runtime permissions, this screen will just be skipped
        composable(route = BlueHeavenRoute.BT_ONBOARDING) {
            BluetoothPermissionPromptScreen(
                blueHeavenServiceViewModel = blueHeavenServiceViewModel,
                navController = navController,
                nextScreen = fourthFinalDestination
            )
        }

        if (needToRequestNotificationPermissions) {
            composable(route = BlueHeavenRoute.NOTO_ONBOARDING) {
                NotificationPermissionPromptScreen(
                    navController = navController,
                    nextScreen = thirdFinalDestination
                )
            }
        }

        if (needToAskSentry) {
            composable(route = BlueHeavenRoute.SENTRY_ONBOARDING) {
                SentryPermissionPromptScreen(preferencesRepository, navController, secondFinalDestination)
            }
        }

        if (needToOnboardBH) {
            composable(route = BlueHeavenRoute.BH_ONBOARDING) {
                BlueHeavenOnboardingScreen(
                    navController = navController,
                    finalDestination = finalDestination,
                    preferences = preferencesRepository,
                    blueHeavenServiceViewModel = blueHeavenServiceViewModel
                )
            }
        }

        // === MAIN SCREENS ===

        composable(route = BlueHeavenRoute.DEBUG) {
            DebugScreen(
                blueHeavenServiceViewModel = blueHeavenServiceViewModel
            )
        }
    }
}

