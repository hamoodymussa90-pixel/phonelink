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
 * Foreground service that glues BtMetadataListener -> PhoneLinkClient and
 * exposes dashboard state. Replace DashboardBus with however your dashboard
 * UI observes data (LiveData/StateFlow/broadcast — this uses a simple object
 * bus to keep the example dependency-free).
 */
class LinkForegroundService : Service(), PhoneLinkClient.Listener, BtMetadataListener.TrackCallback {

    companion object {
        private const val TAG = "LinkForegroundService"
        private const val CHANNEL_ID = "headunit_link"
    }

    private lateinit var link: PhoneLinkClient

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        link = PhoneLinkClient(listener = this)
        link.start()
        BtMetadataListener.callback = this
    }

    override fun onDestroy() {
        BtMetadataListener.callback = null
        link.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---- BtMetadataListener.TrackCallback ----

    override fun onTrack(title: String, artist: String, album: String?, durationMs: Long, localArt: Bitmap?) {
        DashboardBus.track(title, artist, album)
        if (localArt != null) {
            // AVRCP cover art worked — use the phone's real artwork, skip online lookup.
            Log.i(TAG, "Using AVRCP cover art for $artist - $title")
            DashboardBus.artwork(localArt, source = "avrcp")
            // Still bump seq so any late online artwork for the previous track is discarded.
            link.sendTrack(title, artist, album, durationMs)
        } else {
            link.sendTrack(title, artist, album, durationMs)
            DashboardBus.artworkPending()
        }
    }

    // ---- PhoneLinkClient.Listener ----

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
 * Swap for StateFlow/LiveData in your real dashboard.
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
