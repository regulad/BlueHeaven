package xyz.regulad.blueheaven.network.routing

import xyz.regulad.blueheaven.network.NetworkConstants.toStardardLengthHex
import java.nio.ByteBuffer
import java.security.SecureRandom

data class OGM(
    val nodeId: UInt,
    val nonce: UInt,
) {
    companion object {
        // https://academy.nordicsemi.com/courses/bluetooth-low-energy-fundamentals/lessons/lesson-2-bluetooth-le-advertising/topic/advertisement-packet/

        /*
         * This is a factory method to create an OGM with a new nonce
         * Note: this is inherently non-deterministic
         */
        fun ofNodeId(nodeId: UInt, nonceSet: MutableSet<UInt>): OGM {
            val newNonce = getNewNonce()
            // always register the new nonce with the routing table
            nonceSet.add(newNonce)
            return OGM(nodeId, newNonce)
        }

        fun fromBytes(bytes: ByteArray): OGM {
            val buffer = ByteBuffer.wrap(bytes)

            val nodeId = buffer.int.toUInt()
            val nonce = buffer.int.toUInt()

            return OGM(nodeId, nonce)
        }

        private fun getNewNonce(): UInt {
            // this nonce needs to be cryptographically secure (which Random().nextLong() is not) so an attacker could not
            // blast out OGMs that would be sent in the future
            return SecureRandom().nextInt().toUInt()
        }
    }

    fun isEvict(): Boolean {
        return nodeId == 0xFFFFFFFFu
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(8)
        buffer.putInt(nodeId.toInt())
        buffer.putInt(nonce.toInt())

        return buffer.array()
    }

    override fun toString(): String {
        return "OGM(nodeId=${nodeId.toStardardLengthHex()}, nonce=$nonce)"
    }
}
