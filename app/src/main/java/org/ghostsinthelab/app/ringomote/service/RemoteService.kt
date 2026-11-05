package org.ghostsinthelab.app.ringomote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import org.ghostsinthelab.app.ringomote.MainActivity
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.RingomoteApplication
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState

/**
 * Keeps the Bluetooth HID device registered while the app is not on screen.
 *
 * The GATT services live in this process; when it dies, Android deletes them and sends the
 * Apple TV a Service Changed indication, and tvOS then drops the keyboard until the link is
 * made afresh. Verified 2026-11-05: swiping the app away under an open link made every key
 * dead until Bluetooth was toggled. A keyboard does not vanish when its owner looks away, so
 * neither does this one: a foreground service holds the process, and with it the services,
 * for as long as the remote is started. The notification shows the link state and offers
 * Stop, which closes the server for good (the next launch starts it again).
 *
 * The transport itself belongs to [org.ghostsinthelab.app.ringomote.AppContainer]; this
 * service only starts it and keeps the process alive. Whether the user wants it running is
 * remembered ([isWanted]): set by [start], cleared by Stop, read by [BootReceiver] so the
 * remote comes back after a reboot without the app being opened.
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as RingomoteApplication).container
        when (intent?.action) {
            ACTION_STOP -> {
                setWanted(this, false)
                container.applicationScope.launch { container.transport.stop() }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                startInForeground(notification(container.transport.state.value))
                container.applicationScope.launch { container.transport.start() }
                container.transport.state.onEach { state ->
                    notificationManager().notify(NOTIFICATION_ID, notification(state))
                }.launchIn(scope)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(state: TransportState): Notification {
        val text = when (state) {
            is TransportState.Connected -> getString(R.string.notification_connected, state.device.name ?: state.device.address)
            is TransportState.Resyncing -> getString(R.string.notification_resyncing, state.device.name ?: state.device.address)
            TransportState.BluetoothOff -> getString(R.string.notification_bluetooth_off)
            else -> getString(R.string.notification_waiting)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_description)
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.ghostsinthelab.app.ringomote.action.STOP"

        private const val PREFS_NAME = "remote_service"
        private const val KEY_WANTED = "wanted"

        /** Starts (or refreshes) the service and remembers that it is wanted; idempotent. */
        fun start(context: Context) {
            setWanted(context, true)
            ContextCompat.startForegroundService(context, Intent(context, RemoteService::class.java))
        }

        /** Whether the remote was running when last seen, i.e. not stopped from the notification. */
        fun isWanted(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

        private fun setWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).apply()
        }
    }
}
