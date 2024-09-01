package xyz.regulad.blueheaven.network.delegate

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Since BluetoothGattCallbacks are delivered on the Binder thread that blocks further callbacks (https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=Autoconnect%20=%20true,device%20whenever%20it%20becomes%20available.), it can be advantageous
 *
 * @param realCallback The real BluetoothGattCallback that will be called on a coroutine thread
 */
class DelegatedBluetoothGattCallback(
    private val realCallback: BluetoothGattCallback,
    private val coroutineScope: CoroutineScope
): BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt?, status: Int, newState: Int) {
        coroutineScope.launch {
            realCallback.onConnectionStateChange(gatt, status, newState)
        }
    }

    override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt?, status: Int) {
        coroutineScope.launch {
            realCallback.onServicesDiscovered(gatt, status)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicRead(gatt: android.bluetooth.BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?, status: Int) {
        coroutineScope.launch {
            realCallback.onCharacteristicRead(gatt, characteristic, status)
        }
    }

    override fun onCharacteristicWrite(gatt: android.bluetooth.BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?, status: Int) {
        coroutineScope.launch {
            realCallback.onCharacteristicWrite(gatt, characteristic, status)
        }
    }

    override fun onCharacteristicChanged(gatt: android.bluetooth.BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?) {
        coroutineScope.launch {
            realCallback.onCharacteristicChanged(gatt, characteristic)
        }
    }

    override fun onDescriptorRead(gatt: android.bluetooth.BluetoothGatt?, descriptor: android.bluetooth.BluetoothGattDescriptor?, status: Int) {
        coroutineScope.launch {
            realCallback.onDescriptorRead(gatt, descriptor, status)
        }
    }

    override fun onDescriptorWrite(gatt: android.bluetooth.BluetoothGatt?, descriptor: android.bluetooth.BluetoothGattDescriptor?, status: Int) {
        coroutineScope.launch {
            realCallback.onDescriptorWrite(gatt, descriptor, status)
        }
    }

    override fun onReliableWriteCompleted(gatt: android.bluetooth.BluetoothGatt?, status: Int) {
        coroutineScope.launch {
            realCallback.onReliableWriteCompleted(gatt, status)
        }
    }

    override fun onReadRemoteRssi(gatt: android.bluetooth.BluetoothGatt?, rssi: Int, status: Int) {
        coroutineScope.launch {
            realCallback.onReadRemoteRssi(gatt, rssi, status)
        }
    }

    override fun onMtuChanged(gatt: android.bluetooth.BluetoothGatt?, mtu: Int, status: Int) {
        coroutineScope.launch {
            realCallback.onMtuChanged(gatt, mtu, status)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPhyUpdate(gatt: android.bluetooth.BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
        coroutineScope.launch {
            realCallback.onPhyUpdate(gatt, txPhy, rxPhy, status)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPhyRead(gatt: android.bluetooth.BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
        coroutineScope.launch {
            realCallback.onPhyRead(gatt, txPhy, rxPhy, status)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onServiceChanged(gatt: BluetoothGatt) {
        coroutineScope.launch {
            realCallback.onServiceChanged(gatt)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
        value: ByteArray
    ) {
        coroutineScope.launch {
            realCallback.onDescriptorRead(gatt, descriptor, status, value)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        coroutineScope.launch {
            realCallback.onCharacteristicRead(gatt, characteristic, value, status)
        }
    }
}
