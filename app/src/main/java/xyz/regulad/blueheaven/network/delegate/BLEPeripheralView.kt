package xyz.regulad.blueheaven.network.delegate

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.regulad.blueheaven.util.BleConnectionCompat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A Kotlin-coroutine friendly way to interact with a BluetoothGatt safely and reliably
 */
class BLEPeripheralView private constructor(
    private val device: BluetoothDevice,
    private val context: Context,
    private val autoConnect: Boolean,
    private val callback: BLEPeripheralViewCallback = object : BLEPeripheralViewCallback() {}
){
    /**
     * Callbacks for the BLEPeripheralView. There is never any need to handle disconnections, only implement your own logic in the callback.
     */
    abstract class BLEPeripheralViewCallback {
        /**
         * Called when the first connection is made to the peripheral and the connection is stable.
         */
        open suspend fun onFirstConnection(view: BLEPeripheralView) {}

        /**
         * Called when the peripheral is permanently disconnected (no chance of reconnection) or if the initial connection fails unrecoverablbly
         */
        open suspend fun onFinalDisconnection(view: BLEPeripheralView) {}

        /**
         * Called when a characteristic is changed and we are informed using a notification
         */
        open suspend fun onCharacteristicChanged(view: BLEPeripheralView, characteristic: BluetoothGattCharacteristic, value: ByteArray) {}
    }

    companion object {
        private const val RECONNECT_TIMEOUT_MS = 50_00L
        private const val FIRST_CONNECTION_TIMEOUT_MS = 10_000L

        private const val TAG = "PeripheralView"

        // lock used for all non-autoconnect operations
        private val completeNonAutoConnectLock = Mutex()

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        suspend fun BluetoothDevice.connectGattSafe(
            context: Context,
            allowedToUseAutoConnect: Boolean,
            callback: BLEPeripheralViewCallback = object : BLEPeripheralViewCallback() {}
        ): BLEPeripheralView {
            val deviceInCache = this.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN
            if (!deviceInCache) {
                // not yet in bluetooth cache
                Log.w(TAG, "Handing an incoming advertisement before it has been placed in the bluetooth cache; we can't autoconnect")
            }

            // any autoconnections while the device isn't the cache will fail dramatically
            val autoConnect = allowedToUseAutoConnect && deviceInCache

            val view = BLEPeripheralView(this, context, autoConnect, callback)

            if (autoConnect) {
                // autoConnect doesn't need to lock
                view.connect()
            } else {
                completeNonAutoConnectLock.withLock {
                    view.connect()
                }
            }

            return view
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        private fun BluetoothGatt.versionAgnosticWriteCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return this.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = value
                characteristic.writeType = writeType
                return this.writeCharacteristic(characteristic)
            }
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        private fun BluetoothGatt.versionAgnosticWriteDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return this.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                return this.writeDescriptor(descriptor)
            }
        }
    }

    private val triedToOpen = AtomicBoolean(false)

    private lateinit var gatt: BluetoothGatt

    private suspend fun connect(): BluetoothGatt {
        if (!triedToOpen.compareAndSet(false, true)) {
            throw IllegalStateException("openGatt() called more than once")
        }

        if (didDisconnect.get()) {
            throw IllegalStateException("Cannot reconnect to a peripheral that has been disconnected")
        }

        asyncOperationLock.withLock {
            val bleCompatLayer = BleConnectionCompat(context)

            expectingDisconnect.set(true)
            bleCompatLayer.connectGatt(device, autoConnect, internalBluetoothGattCallback)
            gatt = withTimeout(FIRST_CONNECTION_TIMEOUT_MS) {
                connectionStatusReceivedChannel.receive() ?: throw IllegalStateException("Failed to connect to ${device.address}")
            }
            Log.d(TAG, "Got a gatt connection to ${device.address} with autoconnect=$autoConnect back from channel; ready to roll")
            expectingDisconnect.set(false)

            return gatt
        }
    }

    private val didDisconnect = AtomicBoolean(false)

    /**
     * Disconnect from the underlying BluetoothGatt. This is a blocking operation that handles cleaning everything up
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    // in our design, this gets called twice most of the time so we need to lock
    suspend fun disconnect(thisGatt: BluetoothGatt? = null) {
        if (!didDisconnect.compareAndSet(false, true)) {
            return
        }

        asyncOperationLock.withLock {
            expectingDisconnect.set(true)
            connectionStatusReceivedChannel.receive()
            if (::gatt.isInitialized) { // edge cases where we might not have a gatt
                gatt.disconnect()
            } else thisGatt?.disconnect()
            return@withLock
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun forceDisconnect() {
        if (!didDisconnect.compareAndSet(false, true)) {
            return
        }

        cleanUp()
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanUp(thisGatt: BluetoothGatt? = null) {
        didDisconnect.set(true)
        expectingDisconnect.set(false)
        callbackCoroutineScope.cancel()
        if (::gatt.isInitialized) {
            gatt.close()
        } else thisGatt?.close()
    }

    // bluetooth connections are sort of like IO operations
    private val callbackCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val firstConnectionSuccessful = AtomicBoolean(false)
    private val currentConnectionState = AtomicBoolean(false)
    private val lastConnectionAtTimestamp = AtomicLong(0)

    private val internalBluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        lastConnectionAtTimestamp.set(System.currentTimeMillis())

                        if (!firstConnectionSuccessful.compareAndSet(false, true)) {
                            Log.w(
                                TAG,
                                "Connected to ${gatt.device.address} but we already have a connection; simply negotiating a reconnect"
                            )
                            return@launch
                        }

                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.w(TAG, "Failed to connect to ${gatt.device.address} with status $status")
                        }

                        if (!currentConnectionState.compareAndSet(false, true)) {
                            Log.w(TAG, "Connected to ${gatt.device.address} but we are already connected; this is a bug")
                            return@launch
                        }

                        Log.d(TAG, "Connected to ${gatt.device.address}; sending gatt back down channel")

                        this@BLEPeripheralView.connectionStatusReceivedChannel.send(gatt)

                        // always fire the callback *after* we update the connection state; we don't want to create a deadlock since the callback might take the same lock that the channel is waiting to release
                        this@BLEPeripheralView.callback.onFirstConnection(this@BLEPeripheralView)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasFirstConnection = !firstConnectionSuccessful.get()

                        // if we didn't first connect at least once; there is something wrong over the air and we should bail
                        if (wasFirstConnection) {
                            Log.w(TAG, "Failed to connect to ${gatt.device.address}")
                            this@BLEPeripheralView.connectionStatusReceivedChannel.send(null)
                            this@BLEPeripheralView.callback.onFinalDisconnection(this@BLEPeripheralView)
                            this@BLEPeripheralView.cleanUp(gatt)
                            return@launch
                        }

                        if (status == BluetoothGatt.GATT_SUCCESS || status == 19) { // GATT_CONN_TERMINATE_PEER_USER
                            // this was a deliberate disconnect; perhaps due to a timeout?
                            Log.d(TAG, "Disconnected from ${gatt.device.address} due to peer termination")
                            this@BLEPeripheralView.connectionStatusReceivedChannel.send(null)
                            this@BLEPeripheralView.callback.onFinalDisconnection(this@BLEPeripheralView)
                            this@BLEPeripheralView.cleanUp(gatt)
                            return@launch
                        }

                        if (!autoConnect) {
                            // this could only be a hard disconnect; we need to clean up
                            Log.d(TAG, "Disconnected from ${gatt.device.address} and we are not autoconnecting; cleaning up")
                            this@BLEPeripheralView.callback.onFinalDisconnection(this@BLEPeripheralView)
                            this@BLEPeripheralView.cleanUp(gatt)
                            return@launch
                        }

                        // by now, its likely that the error will be 133; we need to wait to see if a reconnection occurs

                        // do NOT call close here; it will recurse endlessly
                        Log.d(TAG, "Disconnected from ${gatt.device.address}; waiting to see if they reconnect")
                        // we need to start a disconnection timeout to see if they will reconnect

                        val timeAtDisconnect = System.currentTimeMillis()
                        delay(RECONNECT_TIMEOUT_MS)
                        // if the timeout has expired and the device is still tracked (isActive is true; would be false if the scope was cancelled)
                        if (lastConnectionAtTimestamp.get() < timeAtDisconnect && isActive) {
                            // we have not reconnected; we need to clean up
                            Log.d(TAG, "Disconnected from ${gatt.device.address} and we have not reconnected; cleaning up")
                            this@BLEPeripheralView.callback.onFinalDisconnection(this@BLEPeripheralView)
                            this@BLEPeripheralView.cleanUp(gatt)
                            return@launch
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                serviceDiscoveryChannel.send(status)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                val value = characteristic.value
                callback.onCharacteristicChanged(this@BLEPeripheralView, characteristic, value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                callback.onCharacteristicChanged(this@BLEPeripheralView, characteristic, value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                writeCharacteristicChannel.send(status)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                writeDescriptorChannel.send(status)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorRead(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readDescriptorChannel.send(null)
                } else {
                    readDescriptorChannel.send(descriptor?.value)
                }
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readDescriptorChannel.send(null)
                } else {
                    readDescriptorChannel.send(value)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readCharacteristicChannel.send(null)
                } else {
                    readCharacteristicChannel.send(characteristic?.value)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            // evac binder thread asap
            callbackCoroutineScope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readCharacteristicChannel.send(null)
                } else {
                    readCharacteristicChannel.send(value)
                }
            }
        }
    }

    // all android GATT functions take a lock; this is the lock for all non-autoconnect operations
    private val asyncOperationLock = Mutex()

    private val expectingDisconnect = AtomicBoolean(false)

    private val connectionStatusReceivedChannel: Channel<BluetoothGatt?> = Channel(1) // only one connection in flight; status is int
    private val writeCharacteristicChannel: Channel<Int> = Channel(1) // only one write in flight; status is int
    private val writeDescriptorChannel: Channel<Int> = Channel(1) // only one write in flight; status is int
    private val serviceDiscoveryChannel: Channel<Int> = Channel(1) // only one service discovery in flight; status is int

    private val readCharacteristicChannel: Channel<ByteArray?> = Channel(1) // only one read in flight; status is int
    private val readDescriptorChannel: Channel<ByteArray?> = Channel(1) // only one read in flight; status is int

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray = asyncOperationLock.withLock {
        val dispatched = gatt.readCharacteristic(characteristic)

        if (!dispatched) {
            throw IllegalStateException("Failed to read characteristic")
        }

        val status = readCharacteristicChannel.receive()
            ?: throw IllegalStateException("Failed to read characteristic")

        return status
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readDescriptor(descriptor: BluetoothGattDescriptor): ByteArray = asyncOperationLock.withLock {
        val dispatched = gatt.readDescriptor(descriptor)

        if (!dispatched) {
            throw IllegalStateException("Failed to read descriptor")
        }

        val status = readDescriptorChannel.receive()
            ?: throw IllegalStateException("Failed to read descriptor")

        return status
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) = asyncOperationLock.withLock {
        val dispatched = gatt.versionAgnosticWriteCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

        if (!dispatched) {
            throw IllegalStateException("Failed to write characteristic")
        }

        val status = writeCharacteristicChannel.receive()

        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("Failed to write characteristic with status $status")
        }
    }

    /**
     * Writes a descriptor to the peripheral.
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray) = asyncOperationLock.withLock {
        val dispatched = gatt.versionAgnosticWriteDescriptor(descriptor, value);

        if (!dispatched) {
            throw IllegalStateException("Failed to write descriptor")
        }

        val status = writeDescriptorChannel.receive()

        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("Failed to write descriptor with status $status")
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    // this operation doesn't lock, we call an operation that does
    suspend fun updateCharacteristicSubscriptionState(characteristic: BluetoothGattCharacteristic, shouldSubscribe: Boolean, writeDescriptor: Boolean = true) {
        gatt.setCharacteristicNotification(characteristic, shouldSubscribe)
        if (writeDescriptor) {
            val (newDescriptor, value) = JavaBleHelper.setNotify(characteristic, shouldSubscribe)
                ?: throw  IllegalStateException("Failed to get descriptor for characteristic ${characteristic.uuid}")
            writeDescriptor(newDescriptor, value)
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun discoverServices(): List<BluetoothGattService> = asyncOperationLock.withLock {
        val discoverServiceStatus = gatt.discoverServices()

        if (!discoverServiceStatus) {
            throw IllegalStateException("Failed to discover services")
        }

        val status = serviceDiscoveryChannel.receive()

        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("Failed to discover services with status $status")
        }

        return gatt.services
    }

    fun getService(uuid: UUID): BluetoothGattService? {
        return gatt.getService(uuid)
    }
}

