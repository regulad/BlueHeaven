package xyz.regulad.blueheaven.network.packet

import android.util.Log
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.*

/**
 * Represents a packet in the Blue Heaven network protocol.
 *
 * This class encapsulates all the necessary information for a packet, including its type,
 * sequence information, source and destination details, and the actual data payload.
 * It also provides methods for packet creation, serialization, and verification.
 *
 * @property packetType The type of the packet, represented as a UByte.
 * @property packetNonce A unique nonce for the packet, used to prevent replay attacks.
 * @property sequenceNumber The sequence number of this packet within a larger message.
 * @property sequenceLength The total number of packets in the complete message.
 * @property destinationNode The UUID of the destination node.
 * @property destinationServiceNumber The service number (analogous to a port) on the destination node.
 * @property sourceNode The UUID of the source node.
 * @property sourceServiceNumber The service number (analogous to a port) on the source node.
 * @property data The actual payload of the packet.
 */
data class Packet(
    val packetType: UByte, /* UpperPacketTypeByte or custom */
    val packetNonce: ULong,
    val sequenceNumber: UShort,
    val sequenceLength: UShort,
    val destinationNode: UInt,
    val destinationServiceNumber: UShort,
    val sourceNode: UInt,
    val sourceServiceNumber: UShort,
    val data: ByteArray
) {
    companion object {
        private const val TAG = "BlueHeavenPacket"
        const val SIGNATURE_SIZE = 64
        const val HEADER_SIZE =
            SIGNATURE_SIZE + 1 /* packetType */ + 8 /* packetNonce */ + 2 /* sequenceNumber */ + 2 /* sequenceLength */ + 4 /* destinationNode */ + 2 /* destinationServiceNumber */ + 4 /* sourceNode */ + 2 /* sourceServiceNumber */

        /**
         * Generates a cryptographically secure nonce.
         *
         * @return A random ULong value to be used as a nonce.
         */
        private fun generateNonce(): ULong {
            val nonce = SecureRandom().nextLong().toULong()
            Log.d(TAG, "Generated nonce: $nonce")
            return nonce
        }

        /**
         * Creates a new packet with all necessary fields except the nonce, which is generated internally.
         *
         * @param packetType The type of the packet.
         * @param sequenceNumber The sequence number of this packet within a larger message.
         * @param sequenceLength The total number of packets in the complete message.
         * @param destinationNode The UUID of the destination node.
         * @param destinationServiceNumber The service number on the destination node.
         * @param sourceNode The UUID of the source node.
         * @param sourceServiceNumber The service number on the source node.
         * @param data The payload of the packet.
         * @param gattServer The internal GATT server to register the nonce with after creation.
         * @return A new Packet instance with a generated nonce.
         */
        fun create(
            seenNonces: MutableSet<ULong>,
            packetType: UByte,
            sequenceNumber: UShort,
            sequenceLength: UShort,
            destinationNode: UInt,
            destinationServiceNumber: UShort,
            sourceNode: UInt,
            sourceServiceNumber: UShort,
            data: ByteArray? = null,
        ): Packet {
            Log.i(
                TAG,
                "Creating new packet: type=$packetType, seq=$sequenceNumber/$sequenceLength, dest=$destinationNode:$destinationServiceNumber, source=$sourceNode:$sourceServiceNumber, dataSize=${data?.size ?: 0}"
            )

            val newNonce = generateNonce()
            seenNonces.add(newNonce) // thread-safe

            return Packet(
                packetType,
                newNonce,
                sequenceNumber,
                sequenceLength,
                destinationNode,
                destinationServiceNumber,
                sourceNode,
                sourceServiceNumber,
                data ?: ByteArray(0)
            )
        }

        /**
         * Reads a packet from a ByteBuffer and optionally verifies its signature.
         *
         * @param buffer The ByteBuffer containing the serialized packet data.
         * @param publicKeyProvider The public key to verify the packet's signature. If null, signature verification is skipped.
         * @param thisDestinationNode The UUID of the local node. If the packet's destination isn't this node, we don't attempt to verify the signature.
         *
         * @return A Pair containing the Packet instance and a Boolean indicating if the signature is valid (or false if no verification was performed).
         */
        fun fromBuffer(
            buffer: ByteBuffer,
            publicKeyProvider: (UInt) -> Collection<Ed25519PublicKeyParameters>,
            thisDestinationNode: UInt
        ): Pair<Packet, Boolean>? {
            buffer.order(ByteOrder.BIG_ENDIAN)

            try {
                val signature = ByteArray(SIGNATURE_SIZE)
                buffer.get(signature)
                val packetType = buffer.get().toUByte()
                val packetNonce = buffer.long.toULong()
                val sequenceNumber = buffer.short.toUShort()
                val sequenceLength = buffer.short.toUShort()
                val destinationNode = buffer.int.toUInt()
                val destinationServiceNumber = buffer.short.toUShort()
                val sourceNode = buffer.int.toUInt()
                val sourceServiceNumber = buffer.short.toUShort()

                val dataSize = buffer.remaining()
                if (dataSize < 0) {
                    Log.e(TAG, "Invalid data size in packet")
                    return null
                }
                val data = ByteArray(dataSize)
                buffer.get(data)

                val packet = Packet(
                    packetType,
                    packetNonce,
                    sequenceNumber,
                    sequenceLength,
                    destinationNode,
                    destinationServiceNumber,
                    sourceNode,
                    sourceServiceNumber,
                    data
                )

                Log.i(
                    TAG,
                    "Packet read: type=$packetType, nonce=$packetNonce, seq=$sequenceNumber/$sequenceLength, dest=$destinationNode:$destinationServiceNumber, source=$sourceNode:$sourceServiceNumber, dataSize=$dataSize"
                )

                val isValid = if (destinationNode == thisDestinationNode) {
                    val publicKey = publicKeyProvider(destinationNode)
                    checkKeySignature(buffer, publicKey, signature)
                } else {
                    false
                }

                return Pair(packet, isValid)
            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Buffer underflow while reading packet: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error while reading packet: ${e.message}")
                return null
            }
        }

        private fun checkKeySignature(
            buffer: ByteBuffer,
            publicKey: Collection<Ed25519PublicKeyParameters>,
            signature: ByteArray
        ): Boolean {
            val digest = SHA256Digest()
            digest.update(buffer.array(), SIGNATURE_SIZE, buffer.capacity() - SIGNATURE_SIZE)
            val hash = ByteArray(digest.digestSize)
            digest.doFinal(hash, 0)

            // Verify the signature using the hash
            var isValid = false
            for (key in publicKey) {
                val verifier = Ed25519Signer()
                verifier.init(false, key)
                verifier.update(hash, 0, hash.size)
                isValid = verifier.verifySignature(signature)
                if (isValid) {
                    break
                }
            }
            return isValid
        }

        /**
         * Quickly reads the destination node from a buffer without verifying the entire packet.
         *
         * @param buffer The ByteBuffer containing the serialized packet data.
         * @return The UUID of the destination node.
         */
        fun readDestinationNode(buffer: ByteBuffer): UInt {
            val readOnlyBuffer = buffer.asReadOnlyBuffer()
            readOnlyBuffer.order(ByteOrder.BIG_ENDIAN)
            readOnlyBuffer.position(SIGNATURE_SIZE + 1 /* packetType */ + 8 /* packetNonce */ + 2 /* sequenceNumber */ + 2 /* sequenceLength */) // Skip to destination node
            return readOnlyBuffer.int.toUInt()
        }

        /**
         * Quickly reads the source node from a buffer without verifying the entire packet.
         *
         * @param buffer The ByteBuffer containing the serialized packet data.
         * @return The UUID of the source node.
         */
        fun readSourceNode(buffer: ByteBuffer): UInt {
            val readOnlyBuffer = buffer.asReadOnlyBuffer()
            readOnlyBuffer.order(ByteOrder.BIG_ENDIAN)
            readOnlyBuffer.position(SIGNATURE_SIZE + 1 /* packetType */ + 8 /* packetNonce */ + 2 /* sequenceNumber */ + 2 /* sequenceLength */ + 4 /* destinationNode */ + 2 /* destinationServiceNumber */) // Skip to source node
            return readOnlyBuffer.int.toUInt()
        }

        /**
         * Quickly reads the nonce from a buffer without verifying the entire packet.
         *
         * @param buffer The ByteBuffer containing the serialized packet data.
         * @return The nonce of the packet.
         */
        fun readNonce(buffer: ByteBuffer): ULong {
            val readOnlyBuffer = buffer.asReadOnlyBuffer()
            readOnlyBuffer.order(ByteOrder.BIG_ENDIAN)
            readOnlyBuffer.position(SIGNATURE_SIZE + 1 /* packetType */) // Skip to nonce
            val nonce = readOnlyBuffer.long.toULong()
            Log.d(TAG, "Read nonce: $nonce")
            return nonce
        }
    }

    /**
     * Serializes the packet into a byte array, including a digital signature.
     *
     * @param privateKey The private key used to sign the packet.
     * @return A byte array containing the serialized and signed packet data.
     */
    fun createBytes(privateKey: Ed25519PrivateKeyParameters): ByteBuffer {
        Log.d(TAG, "Serializing packet to bytes")
        val buffer = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.BIG_ENDIAN)

        // Reserve space for signature
        buffer.position(SIGNATURE_SIZE)

        // Write packet fields
        buffer.put(packetType.toByte())
        buffer.putLong(packetNonce.toLong())
        buffer.putShort(sequenceNumber.toShort())
        buffer.putShort(sequenceLength.toShort())
        buffer.putInt(destinationNode.toInt())
        buffer.putShort(destinationServiceNumber.toShort())
        buffer.putShort(sourceServiceNumber.toShort())
        buffer.put(data)

        // Hash the packet contents (excluding the signature)
        val digest = SHA256Digest()
        digest.update(buffer.array(), SIGNATURE_SIZE, buffer.capacity() - SIGNATURE_SIZE)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)

        // Create and write signature
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(hash, 0, hash.size)
        val signature = signer.generateSignature()

        // Write signature at the beginning
        buffer.position(0)
        buffer.put(signature)

        Log.i(TAG, "Packet serialized: size=${buffer.capacity()}")

        buffer.position(0)
        return buffer.asReadOnlyBuffer()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (packetType != other.packetType) return false
        if (packetNonce != other.packetNonce) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (sequenceLength != other.sequenceLength) return false
        if (!data.contentEquals(other.data)) return false
        if (destinationNode != other.destinationNode) return false
        if (destinationServiceNumber != other.destinationServiceNumber) return false
        if (sourceNode != other.sourceNode) return false
        if (sourceServiceNumber != other.sourceServiceNumber) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetType.hashCode()
        result = 31 * result + packetNonce.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + sequenceLength.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + destinationNode.hashCode()
        result = 31 * result + destinationServiceNumber.hashCode()
        result = 31 * result + sourceNode.hashCode()
        result = 31 * result + sourceServiceNumber.hashCode()
        return result
    }

    fun isBroadcast(): Boolean {
        return destinationNode == 0xFFFFFFFFu
    }
}
