package xyz.regulad.blueheaven.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import xyz.regulad.blueheaven.network.NetworkConstants.PACKET_TRANSMISSION_TIMEOUT_MS
import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex
import xyz.regulad.blueheaven.network.packet.Packet
import xyz.regulad.blueheaven.network.packet.Packet.Companion.HEADER_SIZE
import xyz.regulad.blueheaven.network.packet.Packet.Companion.readDestinationNode
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte
import xyz.regulad.blueheaven.network.routing.OGM
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.Lock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BlueHeavenBackend is the main class responsible for managing Bluetooth Low Energy (BLE)
 * communications in the BlueHeaven network. It handles advertising, connections, packet
 * transmission, and Originator Message (OGM) distribution.
 *
 * @property context The Android application context.
 * @property thisNodeId The unique identifier for this node.
 * @property thisNodePrivateKey The private key for this node, used for packet signing.
 * @property publicKeyProvider The database for storing and retrieving public keys.
 * @property networkEventCallback Callback for handling received packets.
 * @property seenPacketNonces Set of seen packet nonces to prevent duplicate processing.
 * @property seenOgmNonces Set of seen OGM nonces to prevent duplicate processing.
 */
@SuppressLint("MissingPermission") // processed before instantiation
class BlueHeavenBackend(
    private val context: Context,
    private val thisNodeId: UInt,
    private val thisNodePrivateKey: Ed25519PrivateKeyParameters,
    private val publicKeyProvider: BlueHeavenDatabase,
    private val networkEventCallback: NetworkEventCallback,
    private val seenPacketNonces: MutableSet<ULong>,
    private val seenOgmNonces: MutableSet<UInt>
) {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser

    private val ogmQueue: Queue<OGM> = ConcurrentLinkedQueue()
    // device address, connection info

    private val clientConnectionSemaphore = Semaphore(MAX_GATT_CLIENT_CONNECTIONS)
    private val gattClientConnections = ConcurrentHashMap<String, GattClientConnectionInfo>()

    private var mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val connectionTimeoutHandler = Handler(Looper.getMainLooper())

    companion object {
        const val MANUFACTURER_ID = 0xb0bf

        @JvmStatic
        val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("c3f3967b-a5ad-43a1-b780-cfb81c19c4a5")

        @JvmStatic
        val RX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("433012ec-6ef4-4a8a-ad26-b895bcb4e7b8")
        @JvmStatic
        val OGM_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("6f29d215-747e-4a44-aaf0-233ce6513415")
        @JvmStatic
        val THIS_NODE_ID_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("202c201e-79fd-4c39-a2cb-ee938b2ac70e")

        private const val TAG = "BlueHeavenBackend"
        // TODO: inform user that sometimes connections will be dropped, tell them to try restarting their phone (worked on dev)
        private const val GATT_CONNECTION_TIMEOUT_MS = 60_000L * 1 // one minute // bluetooth connections are atrociously slow at bonding
        private const val CLIENT_CONNECTION_TTL_MS = 60_000L * 30 // 30 minutes // to favor new routing pathways, we kill connections that we have made to other nodes

        const val OGM_BROADCAST_INTERVAL_MS = 100L // can be arbitrarily low; but it will consume more bandwidth
        const val SELF_OGM_BROADCAST_INTERVAL_MS = 1000L // we need to be more conservative with our own OGMs; we need to make sure they get out but we don't want to spam the network

        // we have 7 to work with, minus the 1 pair of headphones (or game controller) the user might have connected.
        // and so, we get 6 equally split between server and client
        // if the user has more than 1 pair of headphones or game controllers, they are out of luck TODO: handle this case
        private const val MAX_GATT_SERVER_CONNECTIONS = 3
        private const val MAX_GATT_CLIENT_CONNECTIONS = 3

        @JvmStatic
        // https://devzone.nordicsemi.com/f/nordic-q-a/561/what-does-cccd-mean
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @JvmStatic
        private val ADVERTISING_SETTINGS: AdvertiseSettings

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
//                scanBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
//                scanBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                scanBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            }

            SCAN_SETTINGS = scanBuilder.build()

            // ========================

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
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addManufacturerData(MANUFACTURER_ID, ByteBuffer.allocate(4).putInt(thisNodeId.toInt()).array())
        .build()

    private val gattService =
        BluetoothGattService(SERVICE_UUID.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    RX_CHARACTERISTIC_UUID.uuid,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            addCharacteristic(BluetoothGattCharacteristic(
                OGM_CHARACTERISTIC_UUID.uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                val descriptor = BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // there is no alternative to this; ignore the depreciation
                addDescriptor(descriptor)
            })
            addCharacteristic(BluetoothGattCharacteristic(
                THIS_NODE_ID_CHARACTERISTIC_UUID.uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                value = ByteBuffer.allocate(4).putInt(thisNodeId.toInt()).array()
            })
        }

    private data class GattClientConnectionInfo(
        val gatt: BluetoothGatt?,
        val device: BluetoothDevice,
        val writeStatusChannel: Channel<Boolean>,
        val connectedNodeId: UInt?, // it might not be sent
        // node id, last seen time
        val lastTimeNodeUpdated: ConcurrentHashMap<UInt, Long> = ConcurrentHashMap(),
        val usingLock: Mutex = Mutex(),
    )

    /**
     * Gets the best connections for a given node, sorted from best (most recent response) to worst (longest time since response).
     */
    private fun getBestConnectionsForNode(nodeId: UInt): List<GattClientConnectionInfo> {
        val timeMap = gattClientConnections.values
            .filter { it.lastTimeNodeUpdated.contains(nodeId) }
            .flatMap { connectionInfo ->
                listOf(connectionInfo.lastTimeNodeUpdated[nodeId]!! to connectionInfo)
            }
            .toMap()

        return timeMap.entries.sortedByDescending { it.key }.map { it.value }
    }

    fun getReachableNodes(): Set<UInt> {
        return gattClientConnections.values
            .flatMap { it.lastTimeNodeUpdated.keys }
            .toSet()
    }

    fun getDirectConnections(): Set<UInt> {
        return gattClientConnections
            .values
            .map { it.connectedNodeId }
            .requireNoNulls()
            .toSet()
    }

    private suspend fun withBestConnection(
        destinationNodeId: UInt,
        block: suspend GattClientConnectionInfo.() -> Unit
    ) {
        withTimeout(PACKET_TRANSMISSION_TIMEOUT_MS) {
            while (isActive) {
                val connectionsToTry = getBestConnectionsForNode(destinationNodeId)
                for (connection in connectionsToTry) {
                    try {
                        connection.usingLock.withLock {
                            block(connection)
                        }
                    } catch (e: IllegalStateException) {
                        // we failed to write to this connection; try the next one
                        continue
                    }
                }
                delay(1000) // we failed; let's wait a second before we try sending it again
            }
        }
    }

    /**
     * Starts the BlueHeavenBackend, initializing all necessary components.
     * This method sets up the GATT server, starts advertising, and begins OGM processing.
     *
     * @throws SecurityException if the necessary Bluetooth permissions are not granted.
     * @throws IllegalStateException if Bluetooth is not supported or enabled on the device.
     */
    @SuppressLint("MissingPermission")
    @Throws(SecurityException::class, IllegalStateException::class)
    fun start() {
        if (!bluetoothAdapter.isEnabled) {
            throw IllegalStateException("Bluetooth is not enabled")
        }

        // refresh scopes
        if (!mainScope.isActive) {
            mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        startGattServer()
        startAdvertising()
        startScanner()
        startOgmServing()
    }

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        // TODO: graciously fail when we can't open the GATT server or add the service
        if (gattServer == null) {
            throw IllegalStateException("Failed to open GATT server")
        }

        val successfulServiceAdd = gattServer!!.addService(gattService)

        if (!successfulServiceAdd) {
            throw IllegalStateException("Failed to add service to GATT server")
        }

        Log.d(TAG, "GATT server started successfully")
    }

    /**
     * Stops all operations of the BlueHeavenBackend.
     * This method stops advertising, closes all connections, and cancels all coroutines.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        stopAdvertising()
        stopScanner()
        gattServer?.close()
        // this doesn't wait for the mutex to be released
        gattClientConnections.values.forEach { it.gatt?.close() }
        gattClientConnections.clear()
        mainScope.cancel()
        ioScope.cancel()
    }

    /**
     * Starts advertising the BlueHeaven service.
     * This method sets up a single, persistent advertisement to maintain a consistent
     * hardware address for the device.
     */
    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser?.startAdvertising(ADVERTISING_SETTINGS, advertiseData, advertisingCallback)
    }

    /**
     * Stops the BlueHeaven service advertisement.
     */
    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertisingCallback)
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed to start with error code: $errorCode")
        }
    }

    /**
     * Starts the OGM processing coroutine.
     * This coroutine is responsible for generating and distributing OGMs at regular intervals.
     */
    private fun startOgmServing() {
        ioScope.launch {
            while (isActive) {
                serveOneOgmOffQueue()
                delay(OGM_BROADCAST_INTERVAL_MS)
            }
        }
        ioScope.launch {
            while (isActive) {
                val ogm = OGM.ofNodeId(thisNodeId, seenOgmNonces)
                enqueueOGM(ogm)
                delay(SELF_OGM_BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * Adds an OGM to the queue for processing and distribution.
     *
     * @param ogm The OGM to be enqueued.
     */
    private fun enqueueOGM(ogm: OGM) {
        ogmQueue.offer(ogm)
    }

    /**
     * Processes the OGM queue, distributing OGMs to all connected devices.
     * This method dequeues OGMs and updates the OGM characteristic for all connections.
     */
    private fun serveOneOgmOffQueue() {
        val devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)

        // get an ogm off the queue
        val ogm = ogmQueue.poll() ?: return
        val ogmBytes = ogm.toBytes()

        Log.d(TAG, "Serving $ogm to ${devices.size} listeners")

        // do this after we have poll the OGM off of the queue; we can't let our own OGMs accumulate and stop other OGMs from being served
        if (devices.isEmpty()) {
            return // no devices to serve to; save some cycles (and entropy since we might need to gen a nonce)
        }

        // notify connected devices to the server of the new OGM
        gattServer?.services?.firstOrNull { it.uuid == SERVICE_UUID.uuid }?.let { service ->
            // we are guaranteed to be here
            val characteristic = service.getCharacteristic(OGM_CHARACTERISTIC_UUID.uuid)!!

            for (device in devices) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gattServer?.notifyCharacteristicChanged(device, characteristic, false, ogmBytes)
                    } else {
                        characteristic.value = ogmBytes

                        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to notify OGM to ${device.address}")
                }
            }
        }
    }

    private val gattServerConnections: MutableSet<BluetoothDevice> = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // note: we do NOT try to connect to the device that connected to us, they need to advertise themselves
                    // this is to avoid cases where the hardware address changes

                    if (gattServerConnections.size < MAX_GATT_SERVER_CONNECTIONS) {
                        Log.d(TAG, "Device connected to our server: ${device.address}")
                        gattServerConnections.add(device)
                    } else {
                        Log.d(TAG, "Rejecting connection, server limit reached: ${device.address}")
                        gattServer?.cancelConnection(device)
                    }
                }

                // TODO: mitigate DoS if we are spammed with connections (is this possible to do?)

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (gattServerConnections.remove(device)) {
                        Log.d(TAG, "Device disconnected from our server: ${device.address}")
                    } else {
                        Log.w(TAG, "Device disconnected but wasn't in our server list, desync?: ${device.address}")
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                RX_CHARACTERISTIC_UUID.uuid -> {
                    if (handleIncomingPacket(value)) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } else {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                }

                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            when (characteristic?.uuid) {
                THIS_NODE_ID_CHARACTERISTIC_UUID.uuid -> {
                    val nodeIdBytes = ByteBuffer.allocate(4).putInt(thisNodeId.toInt()).array()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, nodeIdBytes)
                }

                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }

    /**
     * Handles an incoming packet.
     * This method processes the received packet, updating routing information and forwarding
     * the packet if necessary.
     *
     * @param packetBytes The raw byte array of the received packet.
     *
     * @return true if the packet was successfully handled, false otherwise.
     */
    private fun handleIncomingPacket(packetBytes: ByteArray): Boolean {
        val packetBuffer = ByteBuffer.wrap(packetBytes)

        // might seem like it would be faster to grab fields off of the buffer before checking signature,
        // but we might as well just pop the buffer once and then ignore it if the signature is invalid

        val canBePacket = packetBuffer.remaining() >= HEADER_SIZE

        if (!canBePacket) {
            return false
        }

        val (packet, passedSignature) = Packet.fromBuffer(
            packetBuffer,
            publicKeyProvider::readPublicKeysForNode,
            thisNodeId
        ) ?: return false

        val packetAlreadySeen = !seenPacketNonces.add(packet.packetNonce)
        if (packetAlreadySeen) {
            return false // we can't handle this packet because we've already seen it
        }

        if (packet.destinationNode == thisNodeId) {
            // this packet is for us

            if (!passedSignature) {
                // the packet was not signed by the sender

                mainScope.launch {
                    Log.w(TAG, "Received packet with invalid signature from ${packet.sourceNode}")
                    val outgoingPacketType = UpperPacketTypeByte.reject(packet.packetType)
                    val outgoingPacket = Packet.create(
                        seenPacketNonces,
                        outgoingPacketType,
                        1u,
                        1u,
                        packet.sourceNode,
                        packet.sourceServiceNumber,
                        packet.destinationNode,
                        packet.destinationServiceNumber,
                    )
                    val signedOutgoingPacket = outgoingPacket.createBytes(thisNodePrivateKey)
                    transmitPacket(signedOutgoingPacket, outgoingPacket)
                }

                return true // transmission still succeded though; the packet reached the right destination
            }

            val ackPacket = networkEventCallback.onPacketReceived(packet) ?: run {
                val outgoingPacketType = UpperPacketTypeByte.ack(packet.packetType)
                Packet.create(
                    seenPacketNonces,
                    outgoingPacketType,
                    1u,
                    1u,
                    packet.sourceNode,
                    packet.sourceServiceNumber,
                    packet.destinationNode,
                    packet.destinationServiceNumber,
                )
            }

            val signedAckPacket = ackPacket.createBytes(thisNodePrivateKey)

            // we can now asynchroniously try to route the ack back to the sender

            mainScope.launch {
                transmitPacket(signedAckPacket, ackPacket)
            }

            return true // transmission succeded
        } else {
            // this packet is not for us; forward it
            if (packet.destinationNode in getReachableNodes()) {
                mainScope.launch {
                    transmitPacket(packetBuffer, packet)
                }

                return true // we will give our best effort to transmit the packet
            }

            return false // we can't route the packet
        }
    }

    private val clientConnectionObtainingLock = Mutex()

    /**
     * Attempts to establish a client connection to a device that has sent an advertisement.
     * This method is called when a new advertisement is received and there's capacity for
     * a new client connection.
     *
     * It fails silently if the connection limit is reached or if the device is already connected.
     *
     * @param device The BluetoothDevice to connect to.
     */
    @SuppressLint("MissingPermission")
    private suspend fun attemptDeviceConnection(device: BluetoothDevice, connectedNodeId: UInt?) {
        clientConnectionObtainingLock.withLock {
            Log.d(TAG, "Attempting to connect to ${device.address}")

            if (gattClientConnections.containsKey(device.address)) {
                // do not process further if we are already connected
                return
            }

            if (!clientConnectionSemaphore.tryAcquire()) {
                Log.d(TAG, "Client connection limit reached, not connecting to ${device.address}")
                return
            }

            // keep track if we have released the permit
            var thisPermitReleased = false

            fun releasePermitIfNotReleased() {
                if (!thisPermitReleased) {
                    thisPermitReleased = true
                    clientConnectionSemaphore.release()
                } else {
                    Log.w(TAG, "Permit was already released, but something tried to release it again")
                }
            }

            try {
                withTimeout(GATT_CONNECTION_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val writeQueue = Channel<Boolean>()

                        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                when (newState) {
                                    BluetoothProfile.STATE_CONNECTED -> {
                                        Log.d(TAG, "Connected to ${gatt.device.address}")
                                        gatt.discoverServices()
                                    }

                                    BluetoothProfile.STATE_DISCONNECTED -> {
                                        // do NOT call close here; it will recurse endlessly
                                        Log.d(TAG, "Disconnected from ${gatt.device.address}")
                                        gattClientConnections.remove(gatt.device.address)
                                        releasePermitIfNotReleased()
                                        networkEventCallback.onTopologyChanged() // a disconnecting device may have been a bridge; we need to reevaluate the topology

                                        if (continuation.isActive) {
                                            continuation.resumeWithException(IllegalStateException("Disconnected from ${gatt.device.address}"))
                                        }
                                    }
                                }
                            }

                            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    Log.d(TAG, "Services discovered for ${gatt.device.address}")
                                    gattClientConnections[gatt.device.address] =
                                        GattClientConnectionInfo(gatt, gatt.device, writeQueue, connectedNodeId)

                                    // subscribe to notifications from the OGM characteristic
                                    val ogmCharacteristic =
                                        gatt.getService(SERVICE_UUID.uuid)?.getCharacteristic(OGM_CHARACTERISTIC_UUID.uuid)

                                    if (ogmCharacteristic != null) {
                                        gatt.setCharacteristicNotification(ogmCharacteristic, true)
                                    } else {
                                        Log.e(TAG, "OGM characteristic not found for ${gatt.device.address}")
                                    }

                                    if (continuation.isActive) {
                                        continuation.resume(Unit)
                                        scheduleConnectionTimeout(gatt.device.address)
                                    } else {
                                        Log.e(TAG, "Continuation was not active when services were discovered for ${gatt.device.address}! Closing to prevent untracked resources")
                                        gatt.close()
                                    }
                                } else {
                                    Log.e(TAG, "Service discovery failed for ${gatt.device.address}")
                                    gatt.close()
                                }
                            }

                            @Deprecated("Deprecated in Java") // this is for older android verisons, newer versions call the other method directly
                            override fun onCharacteristicChanged(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic
                            ) {
                                val value = characteristic.value // get the value asap before it can change
                                onCharacteristicChanged(gatt, characteristic, value)
                            }

                            override fun onCharacteristicChanged(
                                gatt: BluetoothGatt,
                                characteristic: BluetoothGattCharacteristic,
                                value: ByteArray
                            ) {
                                if (characteristic.uuid == OGM_CHARACTERISTIC_UUID.uuid) {
                                    val ogm = OGM.fromBytes(value)
                                    val goodOgm = updateRoutingInfo(gatt.device.address, ogm)
                                    if (goodOgm) {
                                        enqueueOGM(ogm) // we'll need to forward this
                                    }
                                }
                            }

                            override fun onCharacteristicWrite(
                                gatt: BluetoothGatt?,
                                characteristic: BluetoothGattCharacteristic?,
                                status: Int
                            ) {
                                // we will only ever have one write in flight at a time
                                writeQueue.trySend(status == BluetoothGatt.GATT_SUCCESS)
                            }
                        })

                        if (gatt == null) {
                            throw IllegalStateException("Unknown error; likely PHY")
                        }

                        continuation.invokeOnCancellation {
                            // bail out if the connection has yet to be established
                            Log.w(TAG, "Connection to ${device.address} as a client was cancelled; abandoning")
                            gatt.close()
                        }
                    }
                }
                Log.d(TAG, "Connected to ${device.address} as a client successfully")
            } catch (e: Exception) {
                // our flow was abnormal so we need to release the permit
                releasePermitIfNotReleased()
                Log.w(TAG, "Failed to connect to ${device.address} as a client: ${e.message}")
            }
        }
    }

    /**
     * Schedules a timeout for a client connection.
     * This method ensures that client connections are closed after the CONNECTION_TTL period.
     *
     * @param deviceAddress The address of the connected device.
     */
    private fun scheduleConnectionTimeout(deviceAddress: String) {
        connectionTimeoutHandler.postDelayed({
            gattClientConnections[deviceAddress]?.let { connectionInfo ->
                // this will only be called if the connection is still active
                ioScope.launch {
                    connectionInfo.usingLock.withLock {
                        connectionInfo.gatt?.close()
                        clientConnectionSemaphore.release()
                        gattClientConnections.remove(deviceAddress)
                    }
                }
            }
        }, CLIENT_CONNECTION_TTL_MS)
    }

    /**
     * Updates the routing information based on received OGMs.
     * This method is called when an OGM is received from a connected device.
     *
     * @param deviceAddress The address of the device that sent the OGM.
     * @param ogm The received OGM.
     *
     * @return true if the ogm was relevant and not seen before, false otherwise.
     */
    private fun updateRoutingInfo(deviceAddress: String, ogm: OGM): Boolean {
        val ogmAlreadySeen = !seenOgmNonces.add(ogm.nonce)
        if (ogmAlreadySeen) {
            return false
        }

        Log.d(TAG, "Received OGM from $deviceAddress: ${ogm.nonce}")

        val now = System.currentTimeMillis()
        var changed = false
        gattClientConnections[deviceAddress]?.let { connectionInfo ->
            connectionInfo.lastTimeNodeUpdated[ogm.nodeId] = now
            changed = true
        }

        if (changed) {
            networkEventCallback.onTopologyChanged()
        }

        return true // the ogm did SOMETHING
    }

    /**
     * Transmits a packet to its destination.
     * This method attempts to send the packet using the best available connection
     * based on recent OGM information.
     *
     * @param incomingPacketBuffer The packet to be transmitted.
     * @throws IllegalStateException if no suitable connection is available for the destination.
     */
    @Throws(IllegalStateException::class)
    suspend fun transmitPacket(incomingPacketBuffer: ByteBuffer, preProcessedPacket: Packet? = null) {
        val packetBuffer = incomingPacketBuffer.asReadOnlyBuffer()
        packetBuffer.position(0) // reset position to 0

        if (packetBuffer.remaining() < HEADER_SIZE) {
            throw IllegalArgumentException("Packet buffer is too small")
        }

        val destinationNodeId = preProcessedPacket?.destinationNode ?: readDestinationNode(packetBuffer)

        if (destinationNodeId == thisNodeId) {
            // launch new coroutine scope to handle the packet looping back
            // critically; since the reciver already checks to see if we are the destination we can only get here if the packet came from ourselves
            // that's fine, because it came from ourselves we don't need to check the signature
            mainScope.launch {
                handleIncomingPacket(incomingPacketBuffer.array())
            }

            return
        }

        withBestConnection(destinationNodeId) {
            val gatt = this.gatt
                ?: throw IllegalStateException("No suitable connection for destination node $destinationNodeId")
            val characteristic = gatt.getService(SERVICE_UUID.uuid)?.getCharacteristic(RX_CHARACTERISTIC_UUID.uuid)
                ?: throw IllegalStateException("No RX characteristic found for destination node $destinationNodeId")

            val packetBytes = incomingPacketBuffer.array()

            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val status: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                status =
                    gatt.writeCharacteristic(characteristic, packetBytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = packetBytes
                characteristic.writeType = writeType
                status = gatt.writeCharacteristic(characteristic)
            }

            if (!status) {
                Log.e(TAG, "Failed to write packet to ${gatt.device.address}")
                throw IllegalStateException("Failed to write packet to ${gatt.device.address}")
            }

            // wait for the write callback to be recieved
            val writeCallbackStatus = this.writeStatusChannel.receive()
            if (!writeCallbackStatus) {
                Log.e(TAG, "Failed to write packet to ${gatt.device.address}")
                throw IllegalStateException("Failed to write packet to ${gatt.device.address}")
            }

            // otherwise, we are done!
        }
    }

    private var currentScanCallback: ScanCallback? = null

    /**
     * Scans for nearby BlueHeaven devices and attempts to connect to them.
     * This method initiates a BLE scan to discover nearby devices advertising the BlueHeaven service.
     */
    @SuppressLint("MissingPermission")
    private fun startScanner() {
        val scanCallback = object : ScanCallback() {
            // scan callbacks typically happen on the main thread; we should be careful to not block it
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val nodeIdBytes = result.scanRecord?.manufacturerSpecificData?.get(MANUFACTURER_ID)
                val nodeId = if (nodeIdBytes != null) ByteBuffer.wrap(nodeIdBytes).int.toUInt() else null
                Log.d(TAG, "Received an advertisement from ${result.device.address} node id ${nodeId?.toStardardLengthHex()}")
                val device = result.device
                if (device.address !in gattClientConnections.keys) {
                    mainScope.launch {
                        attemptDeviceConnection(device, nodeId)
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                // one might change the other so the whole thing has to be excuted in a coroutine
                Log.d(TAG, "Batch scan results: ${results?.size}")
                // call on scan result for each result
                results?.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
            }
        }

        bluetoothAdapter.bluetoothLeScanner.startScan(listOf(SCAN_FILTER), SCAN_SETTINGS, scanCallback)
        currentScanCallback = scanCallback
    }

    /**
     * Stops the BLE scan.
     */
    @SuppressLint("MissingPermission")
    private fun stopScanner() {
        if (currentScanCallback != null) {
            bluetoothAdapter.bluetoothLeScanner.stopScan(currentScanCallback)
        }
    }
}

/**
 * Interface for handling received packets.
 */
abstract class NetworkEventCallback {
    /**
     * Called when a packet is received and validated.
     *
     * @param packet The received packet.
     *
     * @return The response packet, if any. This should only be a direct response as an alternative to an ACK, like a handshake in a socket connection.
     */
    open fun onPacketReceived(packet: Packet): Packet? = null

    /**
     * Called when the network topology changes, meaning new nodes might be reachable or unreachable. See [BlueHeavenBackend.getReachableNodes] and [BlueHeavenBackend.getDirectConnections].
     * There is no guarantee that this method will be called immediately after a topology change; nor that the topology has actually changed.
     * This method is not called on the main thread; make sure your implementation is thread-safe.
     */
    open fun onTopologyChanged() = Unit
}
