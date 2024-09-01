package xyz.regulad.blueheaven.util

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.os.Build
import java.nio.ByteBuffer
import java.util.*

fun <T> MutableCollection<T>.versionIndependentRemoveIf(predicate: (T) -> Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        this.removeIf(predicate)
    } else {
        val iterator = this.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) {
                iterator.remove()
            }
        }
    }
}

fun <K, V> MutableMap<K, V>.versionIndependentComputeIfAbsent(key: K, mappingFunction: (K) -> V): V {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return this.computeIfAbsent(key, mappingFunction)
    } else {
        val value = this[key]
        if (value == null) {
            val newValue = mappingFunction(key)
            this[key] = newValue
            return newValue
        } else {
            return value
        }
    }
}

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
    val b = ByteBuffer.wrap(ByteArray(16))
    b.putLong(mostSignificantBits)
    b.putLong(leastSignificantBits)
    return b.array()
}

fun Service.isRunning(): Boolean {
    val manager = this.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Integer.MAX_VALUE)) { // as of android O, only returns true for our own services, which is fine
        if (this::class.java.name == service.service.className) {
            return true
        }
    }
    return false
}

fun <E> Set<E>.pickRandom(): E {
    val index = Random().nextInt(this.size)
    return this.elementAt(index)
}
