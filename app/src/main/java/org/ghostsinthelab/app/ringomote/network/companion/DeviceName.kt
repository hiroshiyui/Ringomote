package org.ghostsinthelab.app.ringomote.network.companion

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * How this phone appears under Settings > Remotes and Devices on the Apple TV: the app name
 * with a phone name in brackets, e.g. "Ringomote (Nokia G50)", so a household with several
 * phones can tell the pairings apart. The phone name is the one set in Ringomote's Settings
 * ([org.ghostsinthelab.app.ringomote.network.NetworkSettings.remoteName]) when there is one,
 * else the one the user gave the phone in the system settings (`device_name`), else the model.
 */
object DeviceName {
    const val APP_NAME = "Ringomote"

    fun forPairing(context: Context, custom: String?): String =
        "$APP_NAME (${custom?.trim()?.takeIf { it.isNotEmpty() } ?: phoneName(context)})"

    fun phoneName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL
}
