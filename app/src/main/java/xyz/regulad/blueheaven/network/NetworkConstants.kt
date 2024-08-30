package xyz.regulad.blueheaven.network

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

object NetworkConstants {
    const val PACKET_TRANSMISSION_TIMEOUT_MS = 60_000L
    const val PACKET_TRANSMISSION_MAX_RTT_MS = PACKET_TRANSMISSION_TIMEOUT_MS * 2

    @JvmStatic
    val RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS = setOfNotNull(
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) Manifest.permission.BLUETOOTH else null,
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) Manifest.permission.BLUETOOTH_ADMIN else null,
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.R) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else null,
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            Manifest.permission.ACCESS_COARSE_LOCATION
        } else null,
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else null,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_ADVERTISE else null,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else null
    )

    fun hasBluetoothHardwareSupport(context: Context): Boolean {
        // bt dr/edr and le is guaranteed by the manifest
        return (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.isMultipleAdvertisementSupported
    }

    fun canOpenBluetooth(context: Context): Boolean {
        val hasHardwareSupport = hasBluetoothHardwareSupport(context)
        val hasPermission = RUNTIME_REQUIRED_BLUETOOTH_PERMISSIONS.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        return hasHardwareSupport && hasPermission
    }

    fun UInt.toStardardLengthHex(): String {
        return this.toString(16).padStart(8, '0').uppercase()
    }
}
