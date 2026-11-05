package org.ghostsinthelab.app.ringomote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothPermissions

/**
 * Starts [RemoteService] after a reboot, so a paired Apple TV finds the remote without the
 * app being opened first.
 *
 * Only when the remote was running before (the user has not tapped Stop) and the Bluetooth
 * permissions are granted; otherwise the service would only fail to open the GATT server.
 * `BOOT_COMPLETED` is exempt from the background start restrictions for a foreground
 * service of the connected-device type.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (RemoteService.isWanted(context) && BluetoothPermissions.allGranted(context)) {
            RemoteService.start(context)
        }
    }
}
