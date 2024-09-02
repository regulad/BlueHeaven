package xyz.regulad.blueheaven.util

import java.nio.ByteBuffer
import java.util.*

suspend fun <K, V> MutableMap<K, V>.suspendingComputeIfAbsent(key: K, mappingFunction: suspend (K) -> V): V {
    val value = this[key]
    if (value == null) {
        val newValue = mappingFunction(key)
        this[key] = newValue
        return newValue
    } else {
        return value
    }
}

fun UUID.asBytes(): ByteArray {
    val b = ByteBuffer.allocate(16)
    b.putLong(mostSignificantBits)
    b.putLong(leastSignificantBits)
    return b.array()
}

fun <E> Set<E>.pickRandom(): E {
    val index = Random().nextInt(this.size)
    return this.elementAt(index)
}
