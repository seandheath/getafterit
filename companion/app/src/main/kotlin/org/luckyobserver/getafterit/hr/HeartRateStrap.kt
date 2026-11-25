package org.luckyobserver.getafterit.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.Locale
import java.util.UUID

/** The chest strap the user paired in Get After It, if any. */
object StrapStore {
    private const val PREFS = "getafterit"

    fun address(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("strap_address", null)

    fun name(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("strap_name", null)

    fun save(context: Context, address: String, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("strap_address", address).putString("strap_name", name ?: address).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("strap_address").remove("strap_name").apply()
    }
}

/**
 * Live heart rate straight from a Bluetooth chest strap's Heart Rate service,
 * about once a second. Other apps on the phone can use the strap
 * at the same time: Android shares the one link. Callers must hold
 * BLUETOOTH_CONNECT (the setup screen asks for it).
 */
@SuppressLint("MissingPermission")
class HeartRateStrap(private val context: Context, private val onBpm: (Int) -> Unit) {

    private var gatt: BluetoothGatt? = null

    fun start(address: String) {
        if (gatt != null) return
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        // autoConnect: Android reconnects by itself when the strap comes back
        // into range or is put on again.
        // getRemoteDevice only accepts upper case; Android 13+ hands associations out in lower case.
        gatt = adapter.getRemoteDevice(address.uppercase(Locale.ROOT))
            .connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        Log.d(TAG, "connecting to strap $address")
    }

    fun stop() {
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "strap connection state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val measurement = gatt.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (measurement == null) {
                Log.w(TAG, "device has no Heart Rate Measurement characteristic")
                return
            }
            gatt.setCharacteristicNotification(measurement, true)
            val cccd = measurement.getDescriptor(CLIENT_CONFIG)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            HeartRateMeasurement.parse(value)?.let(onBpm)
        }

        // Called instead of the one above before Android 13.
        @Deprecated("Superseded on Android 13 by the overload with the value")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            HeartRateMeasurement.parse(characteristic.value)?.let(onBpm)
        }
    }

    companion object {
        private const val TAG = "HeartRateStrap"
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
