package xyz.regulad.blueheaven.network.routing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import xyz.regulad.blueheaven.network.BlueHeavenRouter.Companion.MANUFACTURER_ID
import xyz.regulad.blueheaven.network.BlueHeavenRouter.Companion.SERVICE_UUID
import xyz.regulad.blueheaven.network.NetworkConstants.toHex
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * @param attemptDeviceConnection a function that will be called when a device is found; run on the sync thread
 */
class BlueHeavenBLEScanner(
    context: Context,
    private val attemptDeviceConnection: (device: android.bluetooth.BluetoothDevice, nodeId: UInt?) -> Unit
) {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private val scanLifecycleHandler = Handler(Looper.getMainLooper())
    private val currentScanId = AtomicInteger(0)

    companion object {
        const val TAG = "BlueHeavenBLEScanner"

        const val NOUGAT_MAX_SCAN_TIME = 1000L * 60 * 25 // 25 minutes; Android kills us at 30 minutes
        const val NOUGAT_WAIT_TIME = 1000L * 60 * 5 // 5 minutes

        @JvmStatic
        private val SCAN_FILTER = ScanFilter.Builder()
            .setServiceUuid(SERVICE_UUID)
            .build()

        @JvmStatic
        private val SCAN_SETTINGS: ScanSettings

        init {
            val scanBuilder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // let hardware optimize the num matches we want; don't want to connect to bad nodes
                scanBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                scanBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                scanBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            }

            SCAN_SETTINGS = scanBuilder.build()
        }
    }

    val scanCallback = object : ScanCallback() {
        // scan callbacks typically happen on the main thread; we should be careful to not block it
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val nodeIdBytes = result.scanRecord?.manufacturerSpecificData?.get(MANUFACTURER_ID)
            val nodeId = if (nodeIdBytes != null) ByteBuffer.wrap(nodeIdBytes).int.toUInt() else null
            Log.d(TAG, "Received an advertisement from ${result.device.address} node id ${nodeId?.toHex()}")
            val device = result.device
            attemptDeviceConnection(device, nodeId)
        }

        // we don't use it
//        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
//            // one might change the other so the whole thing has to be excuted in a coroutine
//            Log.d(TAG, "Batch scan results: ${results?.size}")
//            // call on scan result for each result
//            results?.forEach { onScanResult(0, it) }
//        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    private val isScanning = AtomicBoolean(false)

    /**
     * Scans for nearby BlueHeaven devices and attempts to connect to them.
     * This method initiates a BLE scan to discover nearby devices advertising the BlueHeaven service.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!isScanning.compareAndSet(false, true)) {
            Log.w(TAG, "Already scanning")
            return
        }

        val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth is off")
            isScanning.set(false)
            return
        }

        bluetoothLeScanner.startScan(listOf(SCAN_FILTER), SCAN_SETTINGS, scanCallback)

        val thisScanId = currentScanId.incrementAndGet()

        // Android Nougat has a 30 minute limit on BLE scans; we need to restart the scan every 25 minutes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            scanLifecycleHandler.postDelayed({
                if (thisScanId == currentScanId.get()) {
                    close()
                    scanLifecycleHandler.postDelayed({
                        if (thisScanId == currentScanId.get()) {
                            start()
                        }
                    }, NOUGAT_WAIT_TIME)
                }
            }, NOUGAT_MAX_SCAN_TIME)
        }
    }

    /**
     * Stops the BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        if (!isScanning.compareAndSet(true, false)) {
            Log.w(TAG, "Not scanning")
            return
        }

        val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth is off") // our scanning was autostopped
            isScanning.set(false)
            return
        }

        bluetoothLeScanner.stopScan(scanCallback)

        currentScanId.set(0)
    }

    fun isScanning(): Boolean {
        return isScanning.get()
    }
}
