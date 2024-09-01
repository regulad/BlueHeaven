package xyz.regulad.blueheaven

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import xyz.regulad.blueheaven.network.BlueHeavenRouter
import xyz.regulad.blueheaven.network.routing.BlueHeavenBLEAdvertiser
import xyz.regulad.blueheaven.network.routing.BlueHeavenBLEScanner
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository

class BlueHeavenViewModel(application: Application) : AndroidViewModel(application) {
    private var binder: BlueHeavenService.BlueHeavenBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            this@BlueHeavenViewModel.binder = service as BlueHeavenService.BlueHeavenBinder
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            this@BlueHeavenViewModel.binder = null
        }
    }

    fun bindService() {
        Intent(getApplication(), BlueHeavenService::class.java).also { intent ->
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService() {
        if (binder != null) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }

    fun getFrontend(): BlueHeavenService? {
        return binder?.getService()
    }

    fun getPreferences(): UserPreferencesRepository? {
        return binder?.getPreferences()
    }

    fun getDatabase(): BlueHeavenDatabase? {
        return binder?.getDatabase()
    }

    fun getRouter(): BlueHeavenRouter? {
        return binder?.getRouter()
    }

    fun getAdvertiser(): BlueHeavenBLEAdvertiser? {
        return binder?.getAdvertiser()
    }

    fun getScanner(): BlueHeavenBLEScanner? {
        return binder?.getScanner()
    }
}
