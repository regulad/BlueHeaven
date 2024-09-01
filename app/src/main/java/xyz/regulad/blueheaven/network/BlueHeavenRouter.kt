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
import xyz.regulad.blueheaven.network.delegate.BLEPeripheralView
import xyz.regulad.blueheaven.network.delegate.BLEPeripheralView.Companion.connectGattSafe
import xyz.regulad.blueheaven.network.packet.Packet
import xyz.regulad.blueheaven.network.packet.Packet.Companion.HEADER_SIZE
import xyz.regulad.blueheaven.network.packet.Packet.Companion.readDestinationNode
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte
import xyz.regulad.blueheaven.network.routing.OGM
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.util.BLEConst.CCCD_UUID
import xyz.regulad.blueheaven.util.Barrier
import xyz.regulad.blueheaven.util.pickRandom
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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

        // client -> server
        @JvmStatic
        val TX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("433012ec-6ef4-4a8a-ad26-b895bcb4e7b8")

        // server -> client
        @JvmStatic
        val OGM_RX_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("6f29d215-747e-4a44-aaf0-233ce6513415")

        @JvmStatic
        val THIS_NODE_ID_CHARACTERISTIC_UUID: ParcelUuid = ParcelUuid.fromString("202c201e-79fd-4c39-a2cb-ee938b2ac70e")

        private const val TAG = "BlueHeavenRouter"

        private const val SETUP_TIMEOUT_MS = 10_000L // 10 seconds // should be pretty quick; the connection is already established
        private const val CLIENT_CONNECTION_TTL_MS = 60_000L * 30 // 30 minutes // to favor new routing pathways, we kill connections that we have made to other nodes

        const val OGM_BROADCAST_INTERVAL_MS = 50L // can be arbitrarily low; but it will consume more bandwidth
        const val SELF_OGM_BROADCAST_INTERVAL_MS = 1000L // we need to be more conservative with our own OGMs; we need to make sure they get out but we don't want to spam the network

        // we have 7 to work with, minus the 1 pair of headphones (or game controller) the user might have connected.
        // and so, we get 6 equally split between server and client
        // if the user has more than 1 pair of headphones or game controllers, they are out of luck TODO: handle this case
        private const val MAX_GATT_SERVER_CONNECTIONS = 3
        private const val MAX_GATT_CLIENT_CONNECTIONS = 3

        // to favor connections to nodes that don't include nodes we already have access to, we wait for a couple of scans before we connect, then we connect to the best node (the one that we don't have access to)
        private const val REQUIRED_INCOMING_SCANS = 5
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
        runBlocking {
            gattClientConnections.values.forEach { it.view.disconnect() }
        }
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
        val view: BLEPeripheralView,
        val connectedNodeId: UInt?, // it might not be sent
        // node id, last seen time
        val lastTimeNodeUpdated: ConcurrentHashMap<UInt, Long> = ConcurrentHashMap(),
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
                        block(connection)
                        return@withTimeout
                    } catch (e: IllegalStateException) {
                        // we failed to write to this connection; try the next one
                        continue
                    }
                }
                delay(1000) // we failed; let's wait a second before we try sending it again
            }
        }
    }

    private val scanBarrier = Barrier<Pair<BluetoothDevice, UInt?>>(REQUIRED_INCOMING_SCANS)
    private val scanHandlingLock = Mutex()

    // explicitly marked as public to demonstrate that this is passed as a parameter to the BlueHeavenBLEScanner
    public fun handleIncomingAdvertisement(device: BluetoothDevice, connectedNodeId: UInt?) {
        mainScope.launch {
            // the barrier imposes a lock; but its fine because we will only ever be trying to connect to one device at a time anyway thanks to android not letting their devices be autoconnected
            scanBarrier.collect(device to connectedNodeId) { devices ->
                Log.d(TAG, "Collected ${devices.size} devices, attempting to connect to the best one")

                val deviceToConnectTo: Pair<BluetoothDevice, UInt?>
                scanHandlingLock.withLock {
                    // first thing: create a new set with devices that only appear once
                    val deviceSet = devices.asSequence().map { it.first.address }.toSet().map { it to devices.find { d -> d.first.address == it }!!.second }.map { devices.find { d -> d.first.address == it.first }!! }.toSet()
                    val devicesWithKnownNodeIds = deviceSet.filter { it.second != null }

                    if (deviceSet.size == 1 || devicesWithKnownNodeIds.isEmpty()) {
                        // no room to choose; just pick the first one
                        deviceToConnectTo = deviceSet.first()
                        return@withLock
                    }

                    // we have multiple devices to choose from; we need to pick the best one
                    // first: are any of the nodes not in the reachable node ids?
                    val unreachableNodes = devicesWithKnownNodeIds.filter { it.second!! !in getReachableNodeIDs() }
                    if (unreachableNodes.isNotEmpty()) {
                        // we have a node that we can connect to that we don't have access to, prefer that one
                        deviceToConnectTo = unreachableNodes.first()
                        return@withLock
                    }

                    // second: are any of the nodes not in the directly connected node ids?
                    // note: this is an edge case and can only be hit in networks that are so dense that the same node id appears multiple times; or if an attacker is trying to confuse us
                    val nonDirectlyConnectedNodes = devicesWithKnownNodeIds.filter { it.second!! !in getDirectlyConnectedNodeIDs() }
                    if (nonDirectlyConnectedNodes.isNotEmpty()) {
                        // we have a node that we can connect to that we don't have access to, prefer that one
                        deviceToConnectTo = nonDirectlyConnectedNodes.first()
                        return@withLock
                    }

                    // we have done everything we can to filter out the best node; we'll just pick a random one
                    deviceToConnectTo = deviceSet.pickRandom()
                }

                val (newDeviceAddress, newDeviceConnectedNodeId) = deviceToConnectTo

                try {
                    attemptNewDeviceConnectionAsClient(newDeviceAddress, newDeviceConnectedNodeId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to device ${device.address}: ${e.message}")
                }
            }
        }
    }

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

        // most of our devices won't be in the cache because they aren't discoverable; this is for future utilization

        if (device.address in gattClientConnections.keys) {
            Log.d(TAG, "Ignoring connection to ${device.address} because we are already connected")
            return
        }

        Log.d(TAG, "Attempting to connect to ${device.address}")

        if (!clientConnectionSemaphore.tryAcquire()) {
            Log.d(TAG, "Client connection limit reached, not connecting to ${device.address}")
            return
        }

        // keep track if we have released the permit
        var thisPermitReleased = false

        suspend fun doConnectionCleanup(view: BLEPeripheralView?) {
            fun releasePermitIfNotReleased() {
                if (!thisPermitReleased) {
                    thisPermitReleased = true
                    Log.v(TAG, "Releasing permit for client connection to ${device.address}")
                    clientConnectionSemaphore.release()
                } else {
                    Log.w(TAG, "Permit was already released, but something tried to release it again")
                }
            }

            gattClientConnections.remove(device.address)
            enqueueEvictionOgm() // we don't need to evict our own routes; that's handled by r/seremoving the gattClientConnection
            networkEventCallback.onTopologyChanged() // a disconnecting device may have been a bridge; we need to reevaluate the topology
            view?.disconnect()
        }

        // at this point, we are ready to try connecting
        // wait a little to let the bluetooth stack settle
        delay(100)

        try {
            val setupFinishedChannel = Channel<Unit>(1)
            device.connectGattSafe(context, true, object : BLEPeripheralView.BLEPeripheralViewCallback() {
                override suspend fun onFirstConnection(view: BLEPeripheralView) {
                    // wait a little, we won't be locked here
                    Log.d(TAG, "Connected to ${device.address} as a client")
                    delay(100) // wait before discovering services; helps with stability

                    Log.d(TAG, "Discovering services for ${device.address}")
                    val services: List<BluetoothGattService>
                    try {
                        services = view.discoverServices()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to discover services for ${device.address}: ${e.message}")
                        doConnectionCleanup(view)
                        return
                    }

                    Log.d(TAG, "Services discovered for ${device.address}")
                    val bhService = services.find { it.uuid == SERVICE_UUID.uuid }
                    if (bhService == null) {
                        Log.w(TAG, "Service not found for ${device.address}")
                        doConnectionCleanup(view)
                        return
                    }

                    val ogmCharacteristic = bhService.getCharacteristic(OGM_RX_CHARACTERISTIC_UUID.uuid)
                    if (ogmCharacteristic == null) {
                        Log.w(TAG, "OGM characteristic not found for ${device.address}")
                        doConnectionCleanup(view)
                        return
                    }

                    view.subscribeToCharacteristic(ogmCharacteristic, true)

                    scheduleConnectionTimeout(device.address)

                    // finally add the device to the list of connected devices
                    gattClientConnections[device.address] =
                        GattClientConnectionInfo(view, connectedNodeId)

                    setupFinishedChannel.send(Unit)
                }

                override suspend fun onFinalDisconnection(view: BLEPeripheralView) {
                    Log.d(TAG, "Disconnected from ${device.address} as a client")
                    doConnectionCleanup(view)
                }

                override suspend fun onCharacteristicChanged(
                    view: BLEPeripheralView,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    if (characteristic.uuid == OGM_RX_CHARACTERISTIC_UUID.uuid) {
                        val ogm = OGM.fromBytes(value)
                        val goodOgm = updateRoutingInfo(device.address, ogm)
                        if (goodOgm) {
                            enqueueOGM(ogm) // we'll need to forward this
                        }
                    }
                }
            })
            withTimeout(SETUP_TIMEOUT_MS) {
                setupFinishedChannel.receive()
            }
            Log.d(TAG, "Connected to ${device.address} as a client successfully")
        } catch (e: Exception) {
            // our flow was abnormal so we need to release the permit
            Log.w(TAG, "Failed to connect to ${device.address} as a client: ${e.message}")
            doConnectionCleanup(null)
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
                    connectionInfo.view.disconnect()
                    // semaphore release is handled by the disconnect callback
                }
            }
        }, CLIENT_CONNECTION_TTL_MS)
        Log.d(TAG, "Scheduled connection timeout for $deviceAddress")
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
            val characteristic = this.view.getService(SERVICE_UUID.uuid)?.getCharacteristic(TX_CHARACTERISTIC_UUID.uuid)
                ?: throw IllegalStateException("No RX characteristic found for destination node $destinationNodeId")

            val packetBytes = incomingPacketBuffer.array()

            this.view.writeCharacteristic(characteristic, packetBytes)
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
    override fun onPacketReceived(packet: Packet): Packet? = null

    /**
     * Called when the network topology changes, meaning new nodes might be reachable or unreachable. See [BlueHeavenRouter.getReachableNodeIDs] and [BlueHeavenRouter.getDirectlyConnectedNodeIDs].
     * There is no guarantee that this method will be called immediately after a topology change; nor that the topology has actually changed.
     * This method is not called on the main thread; make sure your implementation is thread-safe.
     */
    override fun onTopologyChanged() = Unit
}
