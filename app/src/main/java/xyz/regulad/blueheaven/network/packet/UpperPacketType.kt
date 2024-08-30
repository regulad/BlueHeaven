package xyz.regulad.blueheaven.network.packet

/**
 * The upper packet type represents the type of the packet the upper layer.
 * Only the destination node cares about it.
 */
object UpperPacketTypeByte {
    // bit 1 - ack (meant to signify the end of one part of an exchanged, also used on reject) (returned to the listener)
    // bit 2 - reject (sent when the signature check fails in leiu of an ack)
    // bit 3 - data/syn (1) or terminate/ping (0)
    // bit 4 - further communication required flag (1) or not (0) (never masked)
    // bit 5 - syn
    // bit 6 - fin (finish, for connection termination)
    // bit 7-8 - reserved

    // ack packets that don't return a custom acking packet are not acked

    const val DATA: UByte = 0b0010_0000u
    const val DATA_ACK: UByte = 0b1010_0000u
    const val DATA_REJ: UByte = 0b1110_0000u // when a signature check fails

    const val SYN: UByte = 0b0001_1000u
    const val SYN_ACK: UByte = 0b0000_1000u
    const val SYN_REJ: UByte = 0b1101_1000u // when a signature check fails
    const val SYN_ACK_ACK: UByte = 0b1010_1000u // socket can now open

    const val PING: UByte = 0b0000_0000u
    const val PING_ACK: UByte = 0b1000_0000u
    const val PING_REJ: UByte = 0b1100_0000u // when a signature check fails

    const val FIN: UByte = 0b0000_0100u
    const val FIN_ACK: UByte = 0b1000_0100u
    const val FIN_REJ: UByte = 0b1100_0100u // when a signature check fails

    const val RST: UByte = 0b0100_0000u

    // masks
    private const val ACK_MASK: UByte = 0b1000_0000u
    private const val REJ_MASK: UByte = 0b0100_0000u

    fun reject(packetType: UByte): UByte {
        return packetType or REJ_MASK
    }

    fun ack(packetType: UByte): UByte {
        return packetType or ACK_MASK
    }

    fun isAck(packetType: UByte): Boolean {
        return packetType and 0b1000_0000u == ACK_MASK || isReject(packetType) // or is reject helps combat purposely malformed packets
    }

    fun isReject(packetType: UByte): Boolean {
        return packetType and 0b0100_0000u == REJ_MASK
    }
}
