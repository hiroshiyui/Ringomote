package org.ghostsinthelab.app.ringomote.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime Bluetooth permissions the app needs.
 *
 * On Android 12+ (API 31) `BLUETOOTH_CONNECT` covers registering the HID device, connecting
 * and sending reports, and `BLUETOOTH_ADVERTISE` covers making the phone discoverable. Older
 * versions use the install-time `BLUETOOTH` / `BLUETOOTH_ADMIN`, declared with
 * `maxSdkVersion="30"` in the manifest, so nothing has to be requested there.
 */
object BluetoothPermissions {

    /** Permissions to request at runtime; empty below Android 12. */
    val runtime: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    /** Whether every permission in [runtime] is granted. */
    fun allGranted(context: Context): Boolean = runtime.all { context.isGranted(it) }

    /** Whether the app may connect and send reports. */
    fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.isGranted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun Context.isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
