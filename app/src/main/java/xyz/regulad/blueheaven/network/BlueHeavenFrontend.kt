package xyz.regulad.blueheaven.network

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import xyz.regulad.blueheaven.network.NetworkConstants.PACKET_TRANSMISSION_MAX_RTT_MS
import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex
import xyz.regulad.blueheaven.network.packet.Packet
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte.isAck
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte.isReject
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.util.BoundedThreadSafeLinkedHashSet
import xyz.regulad.blueheaven.util.versionIndependentRemoveIf
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The frontend class for interacting with the BlueHeaven network.
 */
class BlueHeavenFrontend(
    context: Context,
    database: BlueHeavenDatabase,
    private val preferences: UserPreferencesRepository
) : NetworkEventCallback() {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter

    private val seenPacketNonces = BoundedThreadSafeLinkedHashSet<ULong>(1000)
    private val seenOgmNonces = BoundedThreadSafeLinkedHashSet<UInt>(1000)

    val backend: BlueHeavenBackend

    companion object {
        private const val TAG = "BlueHeavenFrontend"
    }

    init {
        if (!NetworkConstants.canOpenBluetooth(context)) {
            throw IllegalStateException("Bluetooth permissions are not granted")
        }
        if (!preferences.isStoreInitialized()) {
            throw IllegalStateException("Database is not initialized")
        }

        backend = BlueHeavenBackend(
            context = context,
            thisNodeId = preferences.getNodeId(),
            thisNodePrivateKey = preferences.getPrivateKey()!!,
            publicKeyProvider = database,
            networkEventCallback = this,
            seenPacketNonces = seenPacketNonces,
            seenOgmNonces = seenOgmNonces
        )
    }

    fun start() {
        backend.start()
    }

    /**
     * Closes the BlueHeaven network adapter. The bound services will not be unbound, but they will recieve no more packets.
     */
    fun close() {
        backend.close()
    }

    private val bindings = ConcurrentHashMap<UShort, NetworkEventCallback>()

    /**
     * Service number reservations:
     * 0: reserved for TCP backhaul
     * 32768-65535: reserved for acks and returning messages
     */
    fun serviceNumberBound(serviceNumber: UShort): Boolean {
        return bindings.containsKey(serviceNumber)
    }

    @Throws(IllegalStateException::class)
    private fun getUnusedServiceNumber(range: UIntRange): UShort {
        for (i in range) {
            val serviceNumber = i.toUShort()
            if (!serviceNumberBound(serviceNumber)) {
                return serviceNumber
            }
        }
        throw IllegalStateException("No unused service numbers")
    }

    fun bind(serviceNumber: UShort, networkEventCallback: NetworkEventCallback) {
        if (bindings.containsKey(serviceNumber)) {
            throw IllegalArgumentException("Service number $serviceNumber is already bound")
        }
        bindings[serviceNumber] = networkEventCallback
    }

    fun unbind(callback: NetworkEventCallback) {
        bindings.entries.versionIndependentRemoveIf { it.value == callback }
    }

    private val topologyChangeListener = mutableSetOf<() -> Unit>()

    fun addTopologyChangeListener(listener: () -> Unit) {
        topologyChangeListener.add(listener)
    }

    fun removeTopologyChangeListener(listener: () -> Unit) {
        topologyChangeListener.remove(listener)
    }

    override fun onTopologyChanged() {
        Log.d(TAG, "Topology has changed! Nodes now reachable from this node: ${backend.getReachableNodes().map { it.toStardardLengthHex() }}")
        topologyChangeListener.forEach { it() }
    }

    override fun onPacketReceived(packet: Packet): Packet? {
        val serviceNumber = packet.destinationServiceNumber
        val callback = bindings[serviceNumber]
        return callback?.onPacketReceived(packet)
    }

    private fun signPacket(packet: Packet): ByteBuffer {
        return packet.createBytes(preferences.getPrivateKey()!!)
    }

    /**
     * Dispatches a packet to the BlueHeaven network and waits for an acknowledgment.
     * @param packetType the type of packet to send
     * @param sequenceNumber the sequence number of the packet
     * @param sequenceLength the length of the sequence
     * @param destinationNode the UUID of the destination node
     * @param destinationServiceNumber the service number of the destination service
     * @param data the data to send
     */
    suspend fun dispatchDataAndWaitForAck(
        packetType: UByte,
        sequenceNumber: UShort,
        sequenceLength: UShort,
        destinationNode: UInt,
        destinationServiceNumber: UShort,
        data: ByteArray,
        ackCallback: NetworkEventCallback? = null
    ) {
        // get a service number in the top 32768 range
        val ackServiceNumber = getUnusedServiceNumber(32768u..65535u)

        val packet = Packet.create(
            seenPacketNonces,
            packetType,
            sequenceNumber,
            sequenceLength,
            preferences.getNodeId(),
            ackServiceNumber,
            destinationNode,
            destinationServiceNumber,
            data
        )
        val signedPacket = signPacket(packet)

        val incomingAckPacket = withTimeout(PACKET_TRANSMISSION_MAX_RTT_MS) {
            suspendCancellableCoroutine {
                // bind to the service number and wait for the ack
                val serverCallback = object : NetworkEventCallback() {
                    override fun onPacketReceived(packet: Packet): Packet? {
                        if (packet.sourceNode != destinationNode) {
                            return null
                        }
                        if (packet.sourceServiceNumber != destinationServiceNumber) {
                            return null
                        }
                        if (packet.sequenceNumber != sequenceNumber) {
                            return null
                        }
                        if (packet.sequenceLength != sequenceLength) {
                            return null
                        }
                        // we already know this is the correct service number
                        if (isReject(packet.packetType)) {
                            it.resumeWithException(IllegalStateException("Packet was rejected"))
                            return null
                        } else if (!isAck(packet.packetType)) {
                            return null
                        }
                        val outgoingAckPacket = ackCallback?.onPacketReceived(packet)
                        it.resume(packet)
                        return outgoingAckPacket
                    }
                }

                launch {
                    bind(ackServiceNumber, serverCallback)
                    try {
                        backend.transmitPacket(signedPacket, packet)
                    } catch (e: Exception) {
                        unbind(serverCallback)
                        it.resumeWithException(e)
                    }
                    it.invokeOnCancellation {
                        unbind(serverCallback)
                    }
                }
            }
        }
    }
}
