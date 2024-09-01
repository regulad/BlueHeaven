package xyz.regulad.blueheaven

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import xyz.regulad.blueheaven.network.BlueHeavenRouter
import xyz.regulad.blueheaven.network.INetworkEventCallback
import xyz.regulad.blueheaven.network.NetworkConstants.PACKET_TRANSMISSION_MAX_RTT_MS
import xyz.regulad.blueheaven.network.NetworkConstants.canOpenBluetooth
import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex
import xyz.regulad.blueheaven.network.NetworkEventCallback
import xyz.regulad.blueheaven.network.packet.Packet
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte.isAck
import xyz.regulad.blueheaven.network.packet.UpperPacketTypeByte.isReject
import xyz.regulad.blueheaven.network.routing.BlueHeavenBLEAdvertiser
import xyz.regulad.blueheaven.network.routing.BlueHeavenBLEScanner
import xyz.regulad.blueheaven.storage.BlueHeavenDatabase
import xyz.regulad.blueheaven.storage.UserPreferencesRepository
import xyz.regulad.blueheaven.util.BoundedThreadSafeLinkedHashSet
import xyz.regulad.blueheaven.util.isRunning
import xyz.regulad.blueheaven.util.versionIndependentRemoveIf
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The frontend class for interacting with the BlueHeaven network.
 */
class BlueHeavenService : Service(), NetworkEventCallback {
    // ==== service stuff ====

    companion object {
        private const val TAG = "BlueHeavenService"

        private const val CHANNEL_ID = "BluetoothServiceChannel"
        private const val NOTIFICATION_ID = 1337
    }

    private val binder = BlueHeavenBinder()

    inner class BlueHeavenBinder : android.os.Binder() {
        fun getService(): BlueHeavenService {
            return this@BlueHeavenService
        }

        fun getPreferences(): UserPreferencesRepository {
            return preferences
        }

        fun getDatabase(): BlueHeavenDatabase {
            return database
        }

        fun getRouter(): BlueHeavenRouter {
            return router
        }

        fun getAdvertiser(): BlueHeavenBLEAdvertiser {
            return advertiser
        }

        fun getScanner(): BlueHeavenBLEScanner {
            return scanner
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueHeaven Bluetooth Mesh Network")
            .setContentText("Connected to the BlueHeaven network")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setLargeIcon(
                BitmapFactory.decodeResource(
                    resources,
                    R.mipmap.ic_launcher
                )
            )
            .setContentIntent(pendingIntent)
            .build()
    }

    // ==== network initialization ====

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private val bluetoothStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)

            if (!this@BlueHeavenService.isRunning()) {
                Log.d(TAG, "Service is not running, not handling bluetooth state change")
                return
            }

            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "Bluetooth is on, starting BlueHeaven")
                    startBluetooth()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "Bluetooth is off, stopping BlueHeaven")
                    closeBluetooth()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        preferences = UserPreferencesRepository(this)
        database = BlueHeavenDatabase(this, preferences.getNodeId(), preferences.getPublicKey())
        router = BlueHeavenRouter(
            context = this,
            thisNodeId = preferences.getNodeId(),
            thisNodePrivateKey = preferences.getPrivateKey(),
            publicKeyProvider = database,
            networkEventCallback = this,
            seenPacketNonces = seenPacketNonces,
            seenOgmNonces = seenOgmNonces,
        )
        advertiser = BlueHeavenBLEAdvertiser(this, preferences.getNodeId())
        scanner = BlueHeavenBLEScanner(this, router::handleIncomingAdvertisement)

        // register listener for bluetooth state
        registerReceiver(bluetoothStatusReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onDestroy() {
        super.onDestroy()
        closeBluetooth()
        unregisterReceiver(bluetoothStatusReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (canOpenBluetooth(this)) {
            startBluetooth()
        }

        return START_STICKY
    }

    private lateinit var preferences: UserPreferencesRepository
    private lateinit var database: BlueHeavenDatabase
    private lateinit var router: BlueHeavenRouter
    private lateinit var advertiser: BlueHeavenBLEAdvertiser
    private lateinit var scanner: BlueHeavenBLEScanner

    // ==== network frontend ====

    private val seenPacketNonces = BoundedThreadSafeLinkedHashSet<ULong>(1000)
    private val seenOgmNonces = BoundedThreadSafeLinkedHashSet<UInt>(1000)

    private val bluetoothStarted = AtomicBoolean(false)

    fun startBluetooth() {
        if (!bluetoothStarted.compareAndSet(false, true)) {
            Log.d(TAG, "Bluetooth already started")
            return
        }

        router.start()
        advertiser.start()
        scanner.start()
    }

    /**
     * Closes the BlueHeaven network adapter. The bound services will not be unbound, but they will recieve no more packets.
     */
    fun closeBluetooth() {
        if (!bluetoothStarted.compareAndSet(true, false)) {
            Log.d(TAG, "Bluetooth already closed")
            return
        }

        router.close()
        advertiser.close()
        scanner.close()
    }

    private val serviceNumberBindings = ConcurrentHashMap<UShort, NetworkEventCallback>()

    /**
     * Service number reservations:
     * 0: reserved for TCP backhaul
     * 32768-65535: reserved for acks and returning messages
     */
    fun serviceNumberBound(serviceNumber: UShort): Boolean {
        return serviceNumberBindings.containsKey(serviceNumber)
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

    fun bindServiceNumber(serviceNumber: UShort, networkEventCallback: NetworkEventCallback) {
        if (serviceNumberBindings.containsKey(serviceNumber)) {
            throw IllegalArgumentException("Service number $serviceNumber is already bound")
        }
        serviceNumberBindings[serviceNumber] = networkEventCallback
    }

    fun unbindServiceNumber(callback: NetworkEventCallback) {
        serviceNumberBindings.entries.versionIndependentRemoveIf { it.value == callback }
    }

    private val topologyChangeListener = mutableSetOf<() -> Unit>()

    fun addTopologyChangeListener(listener: () -> Unit) {
        topologyChangeListener.add(listener)
    }

    fun removeTopologyChangeListener(listener: () -> Unit) {
        topologyChangeListener.remove(listener)
    }

    override fun onTopologyChanged() {
        Log.d(TAG, "Topology has changed! Nodes now reachable from this node: ${router.getReachableNodeIDs().map { it.toStardardLengthHex() }}")
        topologyChangeListener.forEach { it() }
    }

    override fun onPacketReceived(packet: Packet): Packet? {
        val serviceNumber = packet.destinationServiceNumber
        val callback = serviceNumberBindings[serviceNumber]
        return callback?.onPacketReceived(packet)
    }

    private fun signPacket(packet: Packet): ByteBuffer {
        return packet.createBytes(preferences.getPrivateKey())
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
                val serverCallback = object : INetworkEventCallback() {
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
                    bindServiceNumber(ackServiceNumber, serverCallback)
                    try {
                        router.transmitPacket(signedPacket, packet)
                    } catch (e: Exception) {
                        unbindServiceNumber(serverCallback)
                        it.resumeWithException(e)
                    }
                    it.invokeOnCancellation {
                        unbindServiceNumber(serverCallback)
                    }
                }
            }
        }
    }
}
