package xyz.regulad.blueheaven.util

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.os.Build

fun <T> MutableCollection<T>.versionAgnosticRemoveIf(predicate: (T) -> Boolean) {
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

fun <K, V> MutableMap<K, V>.versionAgnosticComputeIfAbsent(key: K, mappingFunction: (K) -> V): V {
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

fun <K, V> MutableMap<K, V>.versionAgnosticPutIfAbsent(key: K, value: V): V? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return this.putIfAbsent(key, value)
    } else {
        val existingValue = this[key]
        if (existingValue == null) {
            this[key] = value
        }
        return existingValue
    }
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
