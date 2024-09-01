package xyz.regulad.blueheaven

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import xyz.regulad.blueheaven.ui.theme.BlueHeavenTheme
import xyz.regulad.blueheaven.ui.navigation.BlueHeavenNavHost

class MainActivity : ComponentActivity() {
    private val blueHeavenViewModel: BlueHeavenViewModel by viewModels()

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = Intent(this, BlueHeavenService::class.java)
        stopService(serviceIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, BlueHeavenService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        enableEdgeToEdge()
        setContent {
            BlueHeavenTheme {
                DisposableEffect(blueHeavenViewModel) {
                    blueHeavenViewModel.bindService()
                    onDispose {
                        blueHeavenViewModel.unbindService()
                    }
                }

                // initialize static state, like the database

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BlueHeavenNavHost(
                        modifier = Modifier.padding(innerPadding),
                        blueHeavenViewModel = blueHeavenViewModel
                    )
                }
            }
        }
    }
}

