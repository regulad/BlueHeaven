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
import xyz.regulad.blueheaven.network.delegate.DelegatedBluetoothGattCallback
import xyz.regulad.blueheaven.network.packet.Packet
import xyz.regulad.blueheaven.network.packet.Packet.Companion.HEADER_SIZE
import xyz.regulad.blueheaven.network.packet.Packet.Companion.readDestinationNode
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte
import xyz.regulad.blueheaven.network.routing.OGM
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.util.BLEConst.CCCD_UUID
import xyz.regulad.blueheaven.util.BleConnectionCompat
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
// NOTICE: fair warning, there is a lot going on here; the client & server code are very intertwined by nature
@SuppressLint("MissingPermission") // processed before instantiation
class BlueHeavenRouter(
    private val context: Context,
    private val thisNodeId: UInt,
    private val thisNodePrivateKey: Ed25519PrivateKeyParameters,
    private val publicKeyProvider: BlueHeavenDatabase,
    private val networkEventCallback: NetworkEventCallback,
    private val seenPacketNonces: MutableSet<ULong>,
    private val seenOgmNonces: MutableSet<UInt>
) {
    private var mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    companion object {
        const val MANUFACTURER_ID = 0xb0bf

        @JvmStatic
        val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("c3f3967b-a5ad-43a1-b780-cfb81c19c4a5")

        // server -> client (currently unused; have to implement chunked notifications/indications)
        @JvmStatic
        val RX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("53ad16f5-75ff-458c-810d-c16dfb1649a7")
        // client -> server
        @JvmStatic
        val TX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("433012ec-6ef4-4a8a-ad26-b895bcb4e7b8")


        // server -> client
        @JvmStatic
        val OGM_RX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("6f29d215-747e-4a44-aaf0-233ce6513415")
        // client -> server (currently unused; have to implement chunked notifications/indications)
        @JvmStatic
        val OGM_TX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("955377ec-05a3-43bb-8be0-95e7148b44fd")

        @JvmStatic
        val THIS_NODE_ID_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("202c201e-79fd-4c39-a2cb-ee938b2ac70e")

        private const val TAG = "BlueHeavenRouter"
        // TODO: inform user that sometimes connections will be dropped, tell them to try restarting their phone (worked on dev)
        private const val GATT_CONNECTION_TIMEOUT_MS = 20_000L // 10 seconds
        private const val CLIENT_CONNECTION_TTL_MS = 60_000L * 30 // 30 minutes // to favor new routing pathways, we kill connections that we have made to other nodes

        const val OGM_BROADCAST_INTERVAL_MS = 50L // can be arbitrarily low; but it will consume more bandwidth
        const val SELF_OGM_BROADCAST_INTERVAL_MS = 1000L // we need to be more conservative with our own OGMs; we need to make sure they get out but we don't want to spam the network

        // we have 7 to work with, minus the 1 pair of headphones (or game controller) the user might have connected.
        // and so, we get 6 equally split between server and client
        // if the user has more than 1 pair of headphones or game controllers, they are out of luck TODO: handle this case
        private const val MAX_GATT_SERVER_CONNECTIONS = 3
        private const val MAX_GATT_CLIENT_CONNECTIONS = 3
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
        startOgmServing()

        this.networkEventCallback.onTopologyChanged() // we need to reevaluate the topology
    }

    /**
     * Stops all operations of the BlueHeavenBackend.
     * This method stops advertising, closes all connections, and cancels all coroutines.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        gattServer?.close()
        gattServer = null
        // this doesn't wait for the mutex to be released
        gattClientConnections.values.forEach { it.gatt?.disconnect() }
        gattClientConnections.clear()
        mainScope.cancel()
        ioScope.cancel()

        this.networkEventCallback.onTopologyChanged() // we need to reevaluate the topology
    }

    // ============================================================================================
    // SERVER STUFF

    private var gattServer: BluetoothGattServer? = null

    private val ogmQueue: Queue<OGM> = ConcurrentLinkedQueue()
    // device address, connection info

    private val gattService =
        BluetoothGattService(SERVICE_UUID.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    TX_CHARACTERISTIC_UUID.uuid,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            addCharacteristic(BluetoothGattCharacteristic(
                OGM_RX_CHARACTERISTIC_UUID.uuid,
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

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        if (gattServer == null) {
            throw IllegalStateException("Failed to open GATT server")
        }

        // if this gatt server was opened already (as would be the case if we restarted), make sure to remove the service

        gattServer!!.services.forEach {
            if (it.uuid == SERVICE_UUID.uuid) {
                gattServer!!.removeService(it)
            }
        }

        val successfulServiceAdd = gattServer!!.addService(gattService)

        if (!successfulServiceAdd) {
            throw IllegalStateException("Failed to add service to GATT server")
        }

        Log.d(TAG, "GATT server started successfully")
    }

    fun isServerRunning(): Boolean {
        return gattServer != null
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
     * Run when a destructive change has been made to the network topology and other nodes need to be notified.
     */
    private fun enqueueEvictionOgm() {
        val evictionOgm = OGM.ofNodeId(0xFFFFFFFFu, seenOgmNonces)
        enqueueOGM(evictionOgm)
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
        val devices = connectedClients.toSet().map { bluetoothAdapter.getRemoteDevice(it) } // get a snapshot of the devices

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
            val characteristic = service.getCharacteristic(OGM_RX_CHARACTERISTIC_UUID.uuid)!!

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

    // the getConnectedDevices behaves very irratically, better to track it ourselves
    private var connectedClients = Collections.synchronizedSet(mutableSetOf<String>())

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // note: we do NOT try to connect to the device that connected to us, they need to advertise themselves
                    // this is to avoid cases where the hardware address changes

                    if (connectedClients.size < MAX_GATT_SERVER_CONNECTIONS) {
                        Log.d(TAG, "New device connected to our server: ${device.address}")
                        connectedClients.add(device.address)
                    } else {
                        Log.d(TAG, "Rejecting connection, server limit reached: ${device.address}")
                        gattServer?.cancelConnection(device)
                    }
                }

                // TODO: mitigate DoS if we are spammed with connections (is this possible to do?)

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected from our server: ${device.address}")
                    connectedClients.remove(device.address) // don't check too close, sometimes nodes reconnect immediately
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
                TX_CHARACTERISTIC_UUID.uuid -> {
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
            Log.d(TAG, "Received packet with invalid size")
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

            // we can now asynchronously try to route the ack back to the sender

            mainScope.launch {
                transmitPacket(signedAckPacket, ackPacket)
            }

            return true // transmission succeded
        } else {
            // this packet is not for us; forward it
            if (packet.destinationNode in getReachableNodeIDs()) {
                mainScope.launch {
                    transmitPacket(packetBuffer, packet)
                }

                return true // we will give our best effort to transmit the packet
            }

            return false // we can't route the packet
        }
    }

    // ============================================================================================
    // CLIENT STUFF

    private val clientConnectionSemaphore = Semaphore(MAX_GATT_CLIENT_CONNECTIONS)
    private val gattClientConnections = ConcurrentHashMap<String, GattClientConnectionInfo>()
    private val connectionTimeoutHandler = Handler(Looper.getMainLooper())

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

    fun getReachableNodeIDs(): Set<UInt> {
        return gattClientConnections.values
            .flatMap { it.lastTimeNodeUpdated.keys }
            .toSet() + thisNodeId + getDirectlyConnectedNodeIDs()
    }

    fun getDirectlyConnectedNodeIDs(): Set<UInt> {
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

    private val currentAttemptingConnections: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // explicitly marked as public to demonstrate that this is passed as a parameter to the BlueHeavenBLEScanner
    public fun handleIncomingAdvertisement(device: BluetoothDevice, connectedNodeId: UInt?) {
        if (device.address in gattClientConnections.keys) {
            Log.d(TAG, "Ignoring advertisement from connected device ${device.address}; already connected")
            return
        }

        if (!currentAttemptingConnections.add(device.address)) {
            Log.d(TAG, "Ignoring advertisement from device ${device.address} because we are already attempting to connect")
            return
        }

        // note that we do NOT check the tag here; it could be spoofed

        mainScope.launch {
            attemptNewDeviceConnectionAsClient(device, connectedNodeId)
        }
    }

    // helper
    private val bleConnectionCompatLayer = BleConnectionCompat(context)

    private val nonAutoConnectMutex = Mutex()

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
    private suspend fun attemptNewDeviceConnectionAsClient(device: BluetoothDevice, connectedNodeId: UInt?) {
        // https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=Autoconnect%20only%20works%20for%20cached%20or%20bonded%20devices!
        val deviceInCache = device.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN
        if (!deviceInCache) {
            // not yet in bluetooth cache
            Log.w(TAG, "Handing an incoming advertisement before it has been placed in the bluetooth cache; we can't autoconnect")
        }
        // most of our devices won't be in the cache because they aren't discoverable; this is for future utilization

        Log.d(TAG, "Attempting to connect to ${device.address}")

        if (!clientConnectionSemaphore.tryAcquire()) {
            Log.d(TAG, "Client connection limit reached, not connecting to ${device.address}")
            currentAttemptingConnections.remove(device.address)
            return
        }

        // keep track if we have released the permit
        var thisPermitReleased = false

        fun releasePermitIfNotReleased() {
            if (!thisPermitReleased) {
                thisPermitReleased = true
                Log.v(TAG, "Releasing permit for client connection to ${device.address}")
                clientConnectionSemaphore.release()
            } else {
                Log.w(TAG, "Permit was already released, but something tried to release it again")
            }
        }

        // at this point, we are ready to try connecting
        // wait a little to let the bluetooth stack settle
        delay(100)

        try {
            withTimeout(GATT_CONNECTION_TIMEOUT_MS) {
                if (deviceInCache) {
                    // we can try to autoconnect!
                    doLowLevelConnection({ releasePermitIfNotReleased() }, device, connectedNodeId, true)
                } else {
                    // we can't autoconnect; we need to manually connect
                    nonAutoConnectMutex.withLock {
                        doLowLevelConnection({ releasePermitIfNotReleased() }, device, connectedNodeId, false)
                    }
                }
            }
            Log.d(TAG, "Connected to ${device.address} as a client successfully")
        } catch (e: Exception) {
            // our flow was abnormal so we need to release the permit
            Log.w(TAG, "Failed to connect to ${device.address} as a client: ${e.message}")
        } finally {
            currentAttemptingConnections.remove(device.address)
        }
    }

    private suspend fun doLowLevelConnection(
        releasePermitIfNotReleased: () -> Unit,
        device: BluetoothDevice,
        connectedNodeId: UInt?,
        canAutoConnect: Boolean
    ) {
        suspendCancellableCoroutine { continuation ->
            val writeQueue = Channel<Boolean>(1)

            val firstConnectionReceived = AtomicBoolean(false)
            val lastConnectionAtTime = AtomicLong(-1L)

            // the connection to the GATT server is easily the most finicky part of the whole system

            val timeoutRunningScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

            fun doConnectionCleanup(gatt: BluetoothGatt, wasEstablished: Boolean) {
                releasePermitIfNotReleased()
                if (wasEstablished) {
                    // this connection was established, so we need to clean up a little more
                    gattClientConnections.remove(gatt.device.address)
                    enqueueEvictionOgm() // we don't need to evict our own routes; that's handled by r/seremoving the gattClientConnection
                    networkEventCallback.onTopologyChanged() // a disconnecting device may have been a bridge; we need to reevaluate the topology
                }
                timeoutRunningScope.cancel()
                gatt.close()
            }

            val gattCallback = DelegatedBluetoothGattCallback(object : BluetoothGattCallback() {
                // ==== handshaking ====

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    val bondState = gatt.device.bondState

                    if (bondState != BluetoothDevice.BOND_NONE) {
                        Log.w(
                            TAG,
                            "Bonded device ${gatt.device.address} is trying to connect; we should not be bonding"
                        )
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            lastConnectionAtTime.set(System.currentTimeMillis())

                            if (!firstConnectionReceived.compareAndSet(false, true)) {
                                Log.w(
                                    TAG,
                                    "Connected to ${gatt.device.address} but we already have a connection; simply negotiating a reconnect"
                                )
                                return
                            }

                            Log.d(TAG, "Connected to ${gatt.device.address}")
                            mainScope.launch {
                                delay(100) // wait a little before discovering services; possible stability bump
                                Log.d(TAG, "Discovering services for ${gatt.device.address}")
                                gatt.discoverServices()
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            val wasFirstConnection = !firstConnectionReceived.get()

                            // if we didn't first connect at least once; there is something wrong over the air and we should bail
                            if (wasFirstConnection) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(IllegalStateException("Disconnected from ${gatt.device.address} before connection was fully established"))
                                }
                                doConnectionCleanup(gatt, false)
                                return
                            }

                            if (status == BluetoothGatt.GATT_SUCCESS || status == 19) { // GATT_CONN_TERMINATE_PEER_USER
                                // this was a deliberate disconnect; perhaps due to a timeout?
                                Log.d(TAG, "Disconnected from ${gatt.device.address} due to peer termination")
                                    doConnectionCleanup(gatt, true)
                                return
                            }

                            if (!canAutoConnect) {
                                // this could only be a hard disconnect; we need to clean up
                                Log.d(TAG, "Disconnected from ${gatt.device.address} due to hard disconnect")
                                doConnectionCleanup(gatt, true)
                                return
                            }

                            // by now, its likely that the error will be 133; we need to wait to see if a reconnection occurs

                            // do NOT call close here; it will recurse endlessly
                            Log.d(TAG, "Disconnected from ${gatt.device.address}; waiting to see if they reconnect")
                            // we need to start a disconnection timeout to see if they will reconnect

                            val timeAtDisconnect = System.currentTimeMillis()
                            timeoutRunningScope.launch {
                                delay(GATT_CONNECTION_TIMEOUT_MS)
                                // if the timeout has expired and the device is still tracked (isActive is true; would be false if the scope was cancelled)
                                if (lastConnectionAtTime.get() < timeAtDisconnect && isActive) {
                                    // we have not reconnected; we need to clean up
                                    doConnectionCleanup(gatt, true)
                                }
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Service discovery failed for ${gatt.device.address}")
                        gatt.disconnect() // call disconnect here; not close
                        return
                    }

                    Log.d(TAG, "Services discovered for ${gatt.device.address}")

                    // subscribe to notifications from the OGM characteristic
                    val ogmCharacteristic =
                        gatt.getService(SERVICE_UUID.uuid)
                            ?.getCharacteristic(OGM_RX_CHARACTERISTIC_UUID.uuid)

                    if (ogmCharacteristic == null) {
                        Log.w(TAG, "OGM characteristic not found for ${gatt.device.address}")
                        gatt.disconnect() // call disconnect here; not close
                        return
                    }

                    gatt.setCharacteristicNotification(ogmCharacteristic, true)
                    bleConnectionCompatLayer.setNotify(gatt, ogmCharacteristic, true)

                    if (!continuation.isActive) {
                        Log.e(
                            TAG,
                            "Continuation was not active when services were discovered for ${gatt.device.address}! Closing to prevent untracked resources"
                        )
                        gatt.disconnect() // call disconnect here; not close
                        return
                    }

                    continuation.resume(Unit)
                    scheduleConnectionTimeout(gatt.device.address)

                    // finally add the device to the list of connected devices
                    gattClientConnections[gatt.device.address] =
                        GattClientConnectionInfo(gatt, gatt.device, writeQueue, connectedNodeId)
                }

                // ==== data handling ====

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
                    if (characteristic.uuid == OGM_RX_CHARACTERISTIC_UUID.uuid) {
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
            }, this@BlueHeavenRouter.mainScope);

            // https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=Autoconnect%20=%20true,device%20whenever%20it%20becomes%20available.
            // using the connection compat helps always use BLE and never encounter any race conditions
            val gatt = bleConnectionCompatLayer.connectGatt(
                device,
                canAutoConnect, // do autoconnect = true so we can issue many connections at once and let the system handle it
                gattCallback,
            )

            if (gatt == null) {
                releasePermitIfNotReleased() // it won't have been released here; in any other case it will be handled on disconnect
                throw IllegalStateException("Unknown error; likely PHY")
            }

            continuation.invokeOnCancellation {
                // bail out if the connection has yet to be established
                Log.w(TAG, "Connection to ${device.address} as a client was cancelled; abandoning")
                // something went terribly wrong
                doConnectionCleanup(gatt, false) // we are closing without disconnecting == bad!
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
                // TODO: an attacker can rapidly connect and disconnect to add more of these to our handler until we run out of memory
                ioScope.launch {
                    connectionInfo.usingLock.withLock {
                        connectionInfo.gatt?.disconnect()
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

        Log.d(TAG, "Received OGM from $deviceAddress: $ogm")

        val now = System.currentTimeMillis()
        var changed = false

        if (ogm.isEvict()) {
            // this is a special OGM that tells us to evict all known routes coming from this device
            // will need to wait for new OGMs to come in to reestablish routes
            gattClientConnections[deviceAddress]?.let { connectionInfo ->
                connectionInfo.lastTimeNodeUpdated.clear()
                changed = true
            }
            changed = true
        } else {
            gattClientConnections[deviceAddress]?.let { connectionInfo ->
                connectionInfo.lastTimeNodeUpdated[ogm.nodeId] = now
                changed = true
            }
        }

        if (changed) {
            networkEventCallback.onTopologyChanged()
        }

        return true // the ogm did SOMETHING, even if it didn't change *our* routing table it needs to go to other nodes in the mesh so it can change theirs
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
            // launch a new coroutine scope to handle the packet looping back
            // critically; since the receiver already checks to see if we are the destination we can only get here if the packet came from ourselves
            // that's fine, because it came from ourselves we don't need to check the signature
            mainScope.launch {
                handleIncomingPacket(incomingPacketBuffer.array())
            }

            return
        }

        withBestConnection(destinationNodeId) {
            val gatt = this.gatt
                ?: throw IllegalStateException("No suitable connection for destination node $destinationNodeId")
            val characteristic = gatt.getService(SERVICE_UUID.uuid)?.getCharacteristic(TX_CHARACTERISTIC_UUID.uuid)
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
}

interface NetworkEventCallback {
    fun onPacketReceived(packet: Packet): Packet?
    fun onTopologyChanged()
}

/**
 * Interface for handling received packets.
 */
abstract class INetworkEventCallback : NetworkEventCallback {
    /**
     * Called when a packet is received and validated.
     *
     * @param packet The received packet.
     *
     * @return The response packet, if any. This should only be a direct response as an alternative to an ACK, like a handshake in a socket connection.
     */
    open override fun onPacketReceived(packet: Packet): Packet? = null

    /**
     * Called when the network topology changes, meaning new nodes might be reachable or unreachable. See [BlueHeavenRouter.getReachableNodeIDs] and [BlueHeavenRouter.getDirectlyConnectedNodeIDs].
     * There is no guarantee that this method will be called immediately after a topology change; nor that the topology has actually changed.
     * This method is not called on the main thread; make sure your implementation is thread-safe.
     */
    open override fun onTopologyChanged() = Unit
}
