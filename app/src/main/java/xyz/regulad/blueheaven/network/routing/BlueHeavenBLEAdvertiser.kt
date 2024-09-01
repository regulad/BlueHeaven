package xyz.regulad.blueheaven.network.routing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.util.Log
import xyz.regulad.blueheaven.network.BlueHeavenRouter.Companion.MANUFACTURER_ID
import xyz.regulad.blueheaven.network.BlueHeavenRouter.Companion.SERVICE_UUID
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class BlueHeavenBLEAdvertiser(
    context: Context,
    thisNodeId: UInt
) {
    companion object {
        const val TAG = "BlueHeavenBLEAdvertiser"

        @JvmStatic
        private val ADVERTISING_SETTINGS: AdvertiseSettings

        init {
            val advertiseBuilder = AdvertiseSettings.Builder()
                // prefer balanced over low latency; if we are going to connect to a node (which we will when we scan/advertise), hope its a good connection (which a low tx power will guarantee)
                // TODO: mitigate jamming attacks
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                advertiseBuilder.setDiscoverable(false)
            }

            ADVERTISING_SETTINGS = advertiseBuilder.build()
        }
    }

    private val advertiseData: AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(SERVICE_UUID)
        .setIncludeDeviceName(false) // makes it too large
        .setIncludeTxPowerLevel(false)
        .addManufacturerData(MANUFACTURER_ID, ByteBuffer.allocate(4).putInt(thisNodeId.toInt()).array())
        .build()


    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed to start with error code: $errorCode")
        }
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser

    private val isAdvertising = AtomicBoolean(false)

    /**
     * Starts advertising the BlueHeaven service.
     * This method sets up a single, persistent advertisement to maintain a consistent
     * hardware address for the device.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!isAdvertising.compareAndSet(false, true)) {
            Log.w(TAG, "Already advertising")
            return
        }

        advertiser?.startAdvertising(ADVERTISING_SETTINGS, advertiseData, advertisingCallback)
    }

    /**
     * Stops the BlueHeaven service advertisement.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        if (!isAdvertising.compareAndSet(true, false)) {
            Log.w(TAG, "Not advertising")
            return
        }

        advertiser?.stopAdvertising(advertisingCallback)
    }

    fun isAdvertising(): Boolean {
        return isAdvertising.get()
    }
}
