package org.ghostsinthelab.app.ringomote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothPermissions

/**
 * Restarts [RemoteService] right after the app has been updated.
 *
 * An update kills the process, and with it the GATT services; the Apple TV then re-reads the
 * table and remembers the phone as having no keyboard until it enumerates again. Nothing can
 * win that race (the TV re-reads within 200 ms), but coming back at once keeps the remote
 * advertising so that the TV's next fresh look, whenever it makes one, finds everything.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (RemoteService.isWanted(context) && BluetoothPermissions.allGranted(context)) {
            RemoteService.start(context)
        }
    }
}
