package com.example.headunitlink

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Listens to the AVRCP Controller MediaSession published by the Android
 * Bluetooth stack (package "com.android.bluetooth") when a phone is connected
 * over A2DP/AVRCP. This is where the iPhone's track title/artist/album show up.
 *
 * BONUS: if the head unit's Android build has AVRCP cover art enabled
 * (AOSP config `avrcp_controller_enable_cover_art`, Android 11+), the REAL
 * artwork the iPhone is displaying arrives in METADATA_KEY_ALBUM_ART /
 * METADATA_KEY_ART — no online lookup needed. We surface it when present so
 * the online lookup is only a fallback.
 *
 * Requires the user to grant Notification Access to this service
 * (Settings > Apps > Special access > Notification access), which is what
 * unlocks MediaSessionManager.getActiveSessions().
 */
class BtMetadataListener : NotificationListenerService() {

    interface TrackCallback {
        /** Fired on every track change. [localArt] is non-null only when AVRCP cover art worked. */
        fun onTrack(title: String, artist: String, album: String?, durationMs: Long, localArt: Bitmap?)
    }

    companion object {
        private const val TAG = "BtMetadataListener"
        private const val BT_PACKAGE = "com.android.bluetooth"

        /** Wire this up from your Activity/Service before the listener connects. */
        var callback: TrackCallback? = null
    }

    private var sessionManager: MediaSessionManager? = null
    private var btController: MediaController? = null
    private var lastKey: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            publish(metadata)
        }

        override fun onSessionDestroyed() {
            detachController()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachBtController(controllers)
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, BtMetadataListener::class.java)
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        attachBtController(sessionManager?.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        detachController()
        super.onListenerDisconnected()
    }

    private fun attachBtController(controllers: List<MediaController>?) {
        val bt = controllers?.firstOrNull { it.packageName == BT_PACKAGE }
        if (bt == null) {
            detachController()
            return
        }
        if (bt.sessionToken == btController?.sessionToken) return
        detachController()
        btController = bt.also { it.registerCallback(controllerCallback) }
        Log.i(TAG, "Attached to Bluetooth AVRCP media session")
        bt.metadata?.let { publish(it) }
    }

    private fun detachController() {
        btController?.unregisterCallback(controllerCallback)
        btController = null
        lastKey = null
    }

    private fun publish(md: MediaMetadata) {
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)

        // De-dupe: AVRCP tends to re-send identical metadata several times.
        val key = "$title|$artist|$album"
        if (key == lastKey) return
        lastKey = key

        // AVRCP 1.6 cover art, present only on builds with BIP enabled.
        val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)

        Log.i(TAG, "Track: $artist - $title (local art: ${art != null})")
        callback?.onTrack(title, artist, album, durationMs, art)
    }
}
