package xyz.regulad.blueheaven

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import xyz.regulad.blueheaven.network.BlueHeavenFrontend
import xyz.regulad.blueheaven.network.NetworkConstants.canOpenBluetooth
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.ui.BHNavHost.BlueHeavenTheme
import xyz.regulad.blueheaven.ui.navigation.BlueHeavenNavHost

class MainActivity : ComponentActivity() {
    private val globalStateViewModel: GlobalStateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlueHeavenTheme {
                val globalState by globalStateViewModel.state.collectAsState()

                // initialize static state, like the database
                LaunchedEffect(Unit) {
                    val userPreferencesRepository = UserPreferencesRepository(this@MainActivity)
                    globalStateViewModel.updatePreferences(userPreferencesRepository)

                    if (!userPreferencesRepository.isStoreInitialized()) {
                        // this is our first launch, we need to create a new public/private keypair
                        userPreferencesRepository.generateAndStoreKeyPair()
                    }

                    val database = BlueHeavenDatabase(
                        this@MainActivity,
                        userPreferencesRepository.getNodeId(),
                        userPreferencesRepository.getPublicKey()!!
                    ) // guaranteed to be non-null
                    globalStateViewModel.updateDatabase(database)

                    if (canOpenBluetooth(this@MainActivity)) {
                        val networkAdapter = BlueHeavenFrontend(this@MainActivity, database, userPreferencesRepository)
                        globalStateViewModel.updateNetworkAdapter(networkAdapter)
                    }
                }

                DisposableEffect(globalState.networkAdapter) {
                    globalState.networkAdapter?.start()
                    onDispose {
                        globalState.networkAdapter?.close()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BlueHeavenNavHost(
                        modifier = Modifier.padding(innerPadding),
                        globalStateViewModel = globalStateViewModel
                    )
                }
            }
        }
    }
}

