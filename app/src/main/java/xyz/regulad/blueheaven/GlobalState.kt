package xyz.regulad.blueheaven

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.regulad.blueheaven.network.BlueHeavenFrontend
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository

data class GlobalState(
    val networkAdapter: BlueHeavenFrontend?,
    val database: BlueHeavenDatabase?,
    val preferences: UserPreferencesRepository?
)

class GlobalStateViewModel : ViewModel() {
    private val _state = MutableStateFlow(
        GlobalState(
            networkAdapter = null,
            database = null,
            preferences = null
        )
    )
    val state: StateFlow<GlobalState> = _state.asStateFlow()

    fun updateNetworkAdapter(networkAdapter: BlueHeavenFrontend) {
        _state.value = _state.value.copy(networkAdapter = networkAdapter)
    }

    fun updateDatabase(database: BlueHeavenDatabase) {
        _state.value = _state.value.copy(database = database)
    }

    fun updatePreferences(preferences: UserPreferencesRepository) {
        _state.value = _state.value.copy(preferences = preferences)
    }
}
