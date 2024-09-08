package xyz.regulad.blueheaven.util

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.navigation.NavController
import java.nio.ByteBuffer
import java.util.*

fun ApplicationInfo.isDebuggable(): Boolean {
    return (this.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
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
    val b = ByteBuffer.allocate(16)
    b.putLong(mostSignificantBits)
    b.putLong(leastSignificantBits)
    return b.array()
}

fun <E> Set<E>.pickRandom(): E {
    val index = Random().nextInt(this.size)
    return this.elementAt(index)
}

/**
 * Returns true if the service is running, false otherwise. Only works for services that are part of the same app.
 */
fun Service.isRunning(): Boolean {
    val manager = this.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Integer.MAX_VALUE)) { // as of android O, only returns true for our own services, which is fine
        if (this::class.java.name == service.service.className) {
            return true
        }
    }
    return false
}

fun NavController.navigateWithoutHistory(route: String) {
    this.navigate(route) {
        popUpTo(this@navigateWithoutHistory.graph.startDestinationId) {
            inclusive = true
        }
        launchSingleTop = true
    }
}


fun Context.launchAppInfoSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

fun Context.versionAgnosticStartServiceForeground(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.startForegroundService(intent)
    } else {
        this.startService(intent)
    }
}
