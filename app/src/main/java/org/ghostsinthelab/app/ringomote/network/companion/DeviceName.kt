package org.ghostsinthelab.app.ringomote.network.companion

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * How this phone appears under Settings > Remotes and Devices on the Apple TV: the app name
 * with the phone's own name in brackets, e.g. "Ringomote (Nokia G50)", so a household with
 * several phones can tell the pairings apart. The phone's name is the one the user gave it in
 * the system settings (`device_name`), else the model.
 */
object DeviceName {
    const val APP_NAME = "Ringomote"

    fun forPairing(context: Context): String = "$APP_NAME (${phoneName(context)})"

    fun phoneName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL
}
