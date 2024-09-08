package xyz.regulad.blueheaven

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import xyz.regulad.blueheaven.BlueHeavenService.Companion.readyToLaunchService
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.ui.navigation.BlueHeavenNavHost
import xyz.regulad.blueheaven.ui.theme.BlueHeavenTheme
import xyz.regulad.blueheaven.util.versionAgnosticStartServiceForeground

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val blueHeavenServiceViewModel: BlueHeavenServiceViewModel by viewModels()

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, BlueHeavenService::class.java)
        stopService(serviceIntent)
    }

    private lateinit var preferences: UserPreferencesRepository
    private lateinit var database: BlueHeavenDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = UserPreferencesRepository(this)
        database = BlueHeavenDatabase(this, preferences)

        // auto-launch if possible
        if (readyToLaunchService(this, preferences)) {
            Log.d(TAG, "Auto-launching service")
            val serviceIntent = Intent(this, BlueHeavenService::class.java)
            versionAgnosticStartServiceForeground(serviceIntent)
        }

        if (preferences.getSentryPermissions() == UserPreferencesRepository.SentryPermissions.GRANTED) {
            initializeSentry(this)
        }

        enableEdgeToEdge()
        setContent {
            BlueHeavenTheme {
                DisposableEffect(blueHeavenServiceViewModel) {
                    blueHeavenServiceViewModel.bindService()
                    onDispose {
                        blueHeavenServiceViewModel.unbindService()
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BlueHeavenNavHost(
                        modifier = Modifier.padding(innerPadding),
                        blueHeavenServiceViewModel = blueHeavenServiceViewModel,
                        preferencesRepository = preferences,
                        database = database,
                    )
                }
            }
        }
    }
}

