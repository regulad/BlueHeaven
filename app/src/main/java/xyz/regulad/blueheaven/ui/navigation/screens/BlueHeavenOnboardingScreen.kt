package xyz.regulad.blueheaven.ui.navigation.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.regulad.blueheaven.BlueHeavenService
import xyz.regulad.blueheaven.BlueHeavenServiceViewModel
import xyz.regulad.blueheaven.R
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.util.navigateWithoutHistory
import xyz.regulad.blueheaven.util.versionAgnosticStartServiceForeground

@Composable
fun BlueHeavenOnboardingScreen(
    navController: NavController,
    finalDestination: String,
    preferences: UserPreferencesRepository,
    blueHeavenServiceViewModel: BlueHeavenServiceViewModel
) {
    val context = LocalContext.current as Activity

    var isLoading by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "BlueHeaven Icon",
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Welcome to BlueHeaven!",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "We're so glad you're here. The hard part is over, click the button below to finish setup and dive in.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Button(
            onClick = {
                coroutineScope.launch {
                    isLoading = true
                    statusLine = "Generating your public & private key..."
                    preferences.generateKeyPair()

                    // launch the service (binding it to the viewmodel is handled in the lifecycle of the viewmodel)
                    val serviceIntent = Intent(context, BlueHeavenService::class.java)
                    context.versionAgnosticStartServiceForeground(serviceIntent)

                    statusLine = "All done! Redirecting you to the main screen..."
                    delay(1000)

                    navController.navigateWithoutHistory(finalDestination)
                }
            },
            modifier = Modifier.padding(top = 24.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(text = "Get Started")
            }
        }

        if (statusLine.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.secondary,
                fontStyle = FontStyle.Italic
            )
        }
    }
}
