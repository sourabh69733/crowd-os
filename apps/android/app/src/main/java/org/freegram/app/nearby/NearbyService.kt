package org.freegram.app.nearby

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.freegram.app.MainActivity
import org.freegram.app.R
import org.freegram.app.media.MediaStore
import org.freegram.app.store.RoomStore

/** Nearby sharing state shared by the service and the screen. */
object NearbyState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val log = MutableStateFlow<List<String>>(emptyList())
    /** Increments after each exchange so the screen can reload its lists. */
    val exchanges = MutableStateFlow(0)

    fun describe(peer: String, report: ExchangeReport) =
        "$peer: ${report.outcome}. Received ${report.received} posts and ${report.photosReceived} photos, " +
            "rejected ${report.rejected}; sent ${report.sent} posts (${report.acknowledged} confirmed stored) and ${report.photosSent} photos."
}

/**
 * Runs [NearbySharing] as a foreground service, so sharing continues with the screen off, under a visible
 * notification. Started only by the user; stops on request or after [MAX_RUN_MS], and is not restarted if killed.
 */
class NearbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sharing: NearbySharing? = null
    private var exchangeCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sharing != null) return START_NOT_STICKY
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Looking for nearby Freegram phones"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        val store = RoomStore.shared(applicationContext)
        sharing = NearbySharing(applicationContext, store, MediaStore.shared(applicationContext), scope, object : NearbySharing.Listener {
            override fun onStatus(text: String) { NearbyState.status.value = text }
            override fun onExchange(peer: String, report: ExchangeReport) {
                exchangeCount++
                NearbyState.log.value = (listOf(NearbyState.describe(peer, report)) + NearbyState.log.value).take(10)
                NearbyState.exchanges.value++
                update("Sharing nearby · $exchangeCount exchanges")
            }
        })
        scope.launch {
            store.initialize()
            sharing?.start()
            NearbyState.running.value = true
        }
        scope.launch {
            delay(MAX_RUN_MS)
            NearbyState.status.value = "Nearby sharing stopped after 2 hours to save battery"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sharing?.stop()
        sharing = null
        scope.cancel()
        NearbyState.running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL, "Nearby sharing", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_nearby)
        .setContentTitle("Freegram nearby sharing")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, NearbyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "nearby"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.freegram.app.nearby.STOP"
        const val MAX_RUN_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, NearbyService::class.java))
        fun stop(context: Context) { context.startService(Intent(context, NearbyService::class.java).setAction(ACTION_STOP)) }
    }
}
