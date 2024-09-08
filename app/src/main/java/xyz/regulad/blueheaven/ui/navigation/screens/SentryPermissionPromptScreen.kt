package xyz.regulad.blueheaven.ui.navigation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import xyz.regulad.blueheaven.R
import xyz.regulad.blueheaven.initializeSentry
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.util.navigateWithoutHistory

@Composable
fun SentryPermissionPromptScreen(
    preferencesRepository: UserPreferencesRepository,
    navController: NavController,
    nextScreen: String
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val sentryPermissions by remember { mutableStateOf(preferencesRepository.getSentryPermissions()) }

    LaunchedEffect(sentryPermissions) {
        if (sentryPermissions == UserPreferencesRepository.SentryPermissions.GRANTED) {
            initializeSentry(context)
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
            imageVector = ImageVector.vectorResource(id = R.drawable.sentry),
            contentDescription = "Sentry Icon",
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Sentry",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "To collect analytics and crash information, BlueHeaven uses Sentry. This data is used to improve the app and fix bugs, and helps us developers tremendously. However, as privacy-conscious individuals ourselves, we understand that you may wish to remain in complete control of your data. As such, our use of Sentry is completely opt-in, and you can choose to enable or disable it at any time. If you would like to help us improve BlueHeaven, consider enabling Sentry. Make sure to review Sentry's privacy policy (linked below) before enabling it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                preferencesRepository.setSentryPermissions(UserPreferencesRepository.SentryPermissions.GRANTED)
                initializeSentry(context)
                navController.navigateWithoutHistory(nextScreen)
            }
        ) {
            Text("Enable Sentry")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                preferencesRepository.setSentryPermissions(UserPreferencesRepository.SentryPermissions.DENIED)
                navController.navigateWithoutHistory(nextScreen)
            },
        ) {
            Text("Skip")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                // go to browser link (https://sentry.io/privacy/)
                uriHandler.openUri("https://sentry.io/privacy/")
            }
        ) {
            Text("View Privacy Policy (Opens in Browser)")
        }
    }
}
