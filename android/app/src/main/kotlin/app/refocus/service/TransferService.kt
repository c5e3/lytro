package app.refocus.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.refocus.MainActivity
import app.refocus.R
import app.refocus.RefocusApp
import app.refocus.data.ProcessingStage
import app.refocus.ui.formatDuration
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps downloads and processing alive while the app is in the background,
 * with a progress notification. Stops itself as soon as nothing is pending.
 */
@OptIn(FlowPreview::class)
class TransferService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, build("Preparing…", null, null, true, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "refocus:transfer").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "refocus:transfer").apply { acquire() }

        val graph = (application as RefocusApp).graph
        lifecycleScope.launch {
            combine(graph.busy, graph.library.items, graph.session.batch, graph.session.state, graph.processor.current) { busy, items, batch, conn, processing ->
                if (!busy) return@combine null
                val downloading = items.values.firstOrNull { it.downloading }
                val queued = items.values.count { it.downloadQueued }
                when {
                    conn.isLive && batch != null -> {
                        val eta = batch.etaSeconds?.let { " · about ${formatDuration(it)} left" } ?: ""
                        val frac = (batch.done + (downloading?.downloadProgress ?: 0f)) / batch.total.coerceAtLeast(1)
                        Content("Downloading ${batch.done + 1} of ${batch.total}",
                            (batch.currentName ?: "") + eta, frac, downloads = true)
                    }
                    conn.isLive && downloading != null ->
                        Content("Downloading ${downloading.name}",
                            if (queued > 1) "${queued - 1} more waiting" else null, downloading.downloadProgress, downloads = true)
                    conn.isLive && queued > 0 -> Content("Downloading pictures", null, null, downloads = true)
                    processing != null -> {
                        val item = items[processing]
                        val stage = when (item?.processing) {
                            ProcessingStage.Depth -> "Estimating depth"
                            else -> "Decoding light field"
                        }
                        val waiting = items.values.count { it.needsProcessing } - 1
                        Content("Processing ${item?.name ?: ""}",
                            stage + if (waiting > 0) " · $waiting more waiting" else "", item?.processingProgress, downloads = false)
                    }
                    else -> Content("Processing pictures", null, null, downloads = false)
                }
            }.sample(500).collect { c ->
                if (c == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notify(build(c.title, c.text, c.progress, c.progress == null, c.downloads))
                }
            }
        }
    }

    private data class Content(val title: String, val text: String?, val progress: Float?, val downloads: Boolean)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            (application as RefocusApp).graph.session.stopDownloads()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits dataSync services; stop gracefully (partial downloads resume later)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun notify(n: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
    }

    private fun build(title: String, text: String?, progress: Float?, indeterminate: Boolean, downloads: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), indeterminate)
        if (downloads) {
            val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Stop downloads", stop)
        }
        return b.build()
    }

    companion object {
        const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.refocus.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}
