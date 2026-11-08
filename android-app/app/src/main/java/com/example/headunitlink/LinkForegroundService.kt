package com.example.headunitlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log

/**
 * Foreground service gluing BtMetadataListener -> (PhoneLinkClient | BleLinkClient)
 * and publishing dashboard state via DashboardBus.
 *
 * Transport is chosen in settings: Wi-Fi hotspot WebSocket (default, fast)
 * or Bluetooth LE (no hotspot needed, artwork is ~300 px).
 */
class LinkForegroundService : Service(), PhoneLinkClient.Listener, BtMetadataListener.TrackCallback {

    companion object {
        private const val TAG = "LinkForegroundService"
        private const val CHANNEL_ID = "headunit_link"
        const val PREFS = "link"
        const val KEY_URL = "server_url"
        const val KEY_BLE = "use_ble"
        const val DEFAULT_URL = "ws://172.20.10.1:8777"
    }

    private var ws: PhoneLinkClient? = null
    private var ble: BleLinkClient? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BLE, false)) {
            Log.i(TAG, "Starting BLE transport")
            ble = BleLinkClient(this, this).also {
                try {
                    it.start()
                } catch (e: SecurityException) {
                    Log.w(TAG, "BLE permissions missing: ${e.message}")
                }
            }
        } else {
            val url = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
            Log.i(TAG, "Starting Wi-Fi transport to $url")
            ws = PhoneLinkClient(url, this).also { it.start() }
        }
        BtMetadataListener.callback = this
    }

    override fun onDestroy() {
        BtMetadataListener.callback = null
        ws?.stop()
        ble?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---- BtMetadataListener.TrackCallback ----

    override fun onTrack(title: String, artist: String, album: String?, durationMs: Long, localArt: Bitmap?) {
        DashboardBus.track(title, artist, album)
        if (localArt != null) {
            // AVRCP cover art worked — use the phone's real artwork immediately.
            Log.i(TAG, "Using AVRCP cover art for $artist - $title")
            DashboardBus.artwork(localArt, source = "avrcp")
        } else {
            DashboardBus.artworkPending()
        }
        // Still ask the iPhone (bumps seq, so stale online art gets discarded).
        ws?.sendTrack(title, artist, album, durationMs)
        ble?.sendTrack(title, artist, album, durationMs)
    }

    // ---- PhoneLinkClient.Listener (shared by both transports) ----

    override fun onConnected() = DashboardBus.linkState(true)
    override fun onDisconnected() = DashboardBus.linkState(false)

    override fun onArtwork(seq: Int, bitmap: Bitmap?, source: String?) {
        if (bitmap != null) DashboardBus.artwork(bitmap, source ?: "online")
        else DashboardBus.artworkPlaceholder()
    }

    override fun onGps(lat: Double, lon: Double, speedMps: Double, headingDeg: Double, altitudeM: Double, hAccM: Double) {
        DashboardBus.gps(lat, lon, speedMps, headingDeg, altitudeM, hAccM)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone Link", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone link active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }
}

/**
 * Minimal observable state holder with jitter smoothing for speed.
 */
object DashboardBus {
    interface Observer {
        fun onTrackChanged(title: String, artist: String, album: String?)
        fun onArtworkChanged(bitmap: Bitmap?, source: String)
        fun onGpsChanged(lat: Double, lon: Double, smoothedSpeedMps: Double, headingDeg: Double)
        fun onLinkStateChanged(connected: Boolean)
    }

    var observer: Observer? = null

    private val speedWindow = ArrayDeque<Double>()

    fun track(title: String, artist: String, album: String?) =
        observer?.onTrackChanged(title, artist, album) ?: Unit

    fun artwork(bitmap: Bitmap, source: String) =
        observer?.onArtworkChanged(bitmap, source) ?: Unit

    fun artworkPending() { /* optionally show spinner */ }

    fun artworkPlaceholder() = observer?.onArtworkChanged(null, "placeholder") ?: Unit

    fun linkState(connected: Boolean) = observer?.onLinkStateChanged(connected) ?: Unit

    fun gps(lat: Double, lon: Double, speedMps: Double, headingDeg: Double, altitudeM: Double, hAccM: Double) {
        // Smooth speed over the last 3 valid samples to avoid needle jitter.
        if (speedMps >= 0) {
            speedWindow.addLast(speedMps)
            while (speedWindow.size > 3) speedWindow.removeFirst()
        }
        val smoothed = if (speedWindow.isEmpty()) -1.0 else speedWindow.average()
        observer?.onGpsChanged(lat, lon, smoothed, headingDeg)
    }
}
