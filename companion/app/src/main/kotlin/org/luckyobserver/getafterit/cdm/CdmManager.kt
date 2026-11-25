package org.luckyobserver.getafterit.cdm

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.ParcelUuid
import org.luckyobserver.getafterit.hr.HeartRateStrap
import org.luckyobserver.getafterit.hr.StrapStore
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * CompanionDeviceManager (CDM) wrapper.
 *
 * An active CDM association exempts us from Android 12+'s ban on starting
 * a foreground service from the background, which is what a run started
 * from the watch while the app is closed does.
 *
 * Filter shape — load-bearing. AOSP's
 * `CompanionDeviceDiscoveryService.checkBoundDevicesIfNeeded()` (see
 * `packages/CompanionDeviceManager/src/com/android/companiondevicemanager/
 * CompanionDeviceDiscoveryService.java`) takes the "no scan required"
 * fast path that surfaces the watch even when it isn't BLE-advertising
 * only when **all** of: (a) at least one classic [BluetoothDeviceFilter]
 * is present, (b) the filter has `setAddress(...)` set, (c)
 * `setSingleDevice(true)`. The address it matches against is whatever
 * `BluetoothDevice.getAddress()` returns for entries in
 * `BluetoothAdapter.getBondedDevices()` — i.e. the **BR/EDR public
 * address** (e.g. `12:34:56:78:9A:BC`), not the LE random static
 * address. PebbleKit's `WatchIdentifier` exposes only the LE address
 * (`C1:23:45:67:89:AB`-style, top two bits = random-static), so passing
 * that through to CDM silently misses the bonded match and the dialog
 * falls back to scanning. We instead enumerate
 * `BluetoothAdapter.getBondedDevices()` directly, find the Pebble by
 * `name.startsWith("Pebble")`, and use that record's `address` — the
 * exact string AOSP will compare against.
 */
object CdmManager {

    private const val TAG = "CdmManager"

    /** True when the watch (not just a chest strap) has a companion association. */
    fun isPaired(context: Context): Boolean {
        val strap = StrapStore.address(context)
        return associatedAddresses(context).any { !it.equals(strap, ignoreCase = true) }
    }

    private fun associatedAddresses(context: Context): List<String> {
        val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        return if (Build.VERSION.SDK_INT >= 33) {
            cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        } else {
            @Suppress("DEPRECATION") cdm.associations
        }
    }

    /**
     * Opens Android's picker for Bluetooth devices offering the Heart Rate
     * service (a chest strap). No scan permission needed: the system scans.
     * On Android 13+ the chosen device arrives in [onChosen]; before that the
     * caller reads it from the launcher's result ([strapFromResult]).
     */
    fun requestStrapPairing(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onChosen: (address: String, name: String?) -> Unit,
    ) {
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(HeartRateStrap.HEART_RATE_SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(request, { it.run() }, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                    val address = associationInfo.deviceMacAddress?.toString() ?: return
                    onChosen(address, associationInfo.displayName?.toString())
                }
                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "strap association failed: $error")
                }
            })
        } else {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(intentSender: IntentSender) {
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "strap association failed: $error")
                }
            }, null)
        }
    }

    /** Before Android 13, the chosen strap comes back in the picker's result intent. */
    @SuppressLint("MissingPermission")
    fun strapFromResult(data: Intent?): Pair<String, String?>? {
        if (Build.VERSION.SDK_INT >= 33) return null
        @Suppress("DEPRECATION")
        val result = data?.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE) ?: return null
        return result.device.address to result.device.name
    }

    fun findBondedPebbleAddress(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "findBondedPebbleAddress: BLUETOOTH_CONNECT not granted")
            return null
        }
        val bm = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = bm.adapter ?: return null
        val bonded: Set<BluetoothDevice> = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices threw SecurityException", e)
            return null
        }
        val pebble = bonded.firstOrNull { device ->
            // BluetoothDevice.getName() requires BLUETOOTH_CONNECT on API
            // 31+; we just gated on that. Wrap in try in case a specific
            // device's name read still throws on a quirky OEM build.
            val name = try { device.name } catch (_: SecurityException) { null }
            name?.startsWith("Pebble", ignoreCase = true) == true
        }
        if (pebble == null) {
            val names = bonded.joinToString(", ") {
                runCatching { it.name }.getOrNull() ?: it.address
            }
            Log.d(TAG, "findBondedPebbleAddress: no Pebble in bonded set [$names]")
        }
        return pebble?.address
    }

    /**
     * Dispatch a CDM associate request. The system shows the bonded-device
     * fast-path dialog (no scan), the user taps Allow, and Android
     * activates `REQUEST_COMPANION_RUN_IN_BACKGROUND` +
     * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` for
     * our UID — together they let our service go foreground from the background.
     *
     * Returns true if a request was dispatched, false if the prerequisites
     * weren't met (caller can surface a hint to the user).
     */
    fun requestPairing(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "CDM not available below API 26; pairing not attempted")
            return false
        }
        val bondedAddress = findBondedPebbleAddress(activity)
        if (bondedAddress.isNullOrBlank()) {
            // Without the bonded address the fast path can't trigger and
            // the dialog falls back to active scanning, which is
            // GAP-silent for a bonded Pebble. Refuse rather than offer a
            // guaranteed-failing UX.
            Log.w(TAG, "requestPairing: Pebble not in bondedDevices — pair the watch in the Pebble app first")
            return false
        }
        val cdm = activity.getSystemService(Context.COMPANION_DEVICE_SERVICE)
            as? CompanionDeviceManager ?: run {
                Log.w(TAG, "CompanionDeviceManager unavailable on this device")
                return false
            }

        // Classic-BT filter pinned to the bonded Pebble's BR/EDR address.
        // AOSP's CompanionDeviceDiscoveryService.findMatch evaluates
        // BluetoothDeviceFilter.matches() against
        // BluetoothAdapter.getBondedDevices(); the comparison is
        // String.equals on the address, so we MUST pass the same string
        // that getBondedDevices() reports — which is the BR/EDR public
        // address, never the LE random static.
        Log.d(TAG, "requestPairing: building filter address='$bondedAddress' singleDevice=true")
        val filter = BluetoothDeviceFilter.Builder()
            .setAddress(bondedAddress)
            .build()
        // setSingleDevice(true) is the *other* half of the fast-path
        // trigger. setDeviceProfile(...) is deliberately NOT used —
        // DEVICE_PROFILE_WATCH overrides the bonded check and forces the
        // dialog into a "scanning for watches" mode that never finds a
        // non-advertising Pebble.
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        if (Build.VERSION.SDK_INT >= 33) {
            // Modern callback path. Android delivers the dialog
            // IntentSender via onAssociationPending; we forward it to the
            // ActivityResultLauncher.
            val executor: Executor = Executor { it.run() }
            cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    Log.d(TAG, "associate: onAssociationPending — launching dialog")
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                    Log.d(TAG, "associate: onAssociationCreated id=${associationInfo.id}")
                }

                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "associate: failed: $error")
                }
            })
        } else {
            // API 26-32: legacy callback path. onDeviceFound delivers an
            // IntentSender directly; forward the same way.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(TAG, "associate: onDeviceFound — launching dialog")
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "associate: failed: $error")
                }
            }, /* handler= */ null)
        }
        return true
    }
}
