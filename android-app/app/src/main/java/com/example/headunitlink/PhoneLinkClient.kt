package com.example.headunitlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * WebSocket client that talks to the iPhone companion app (protocol v1).
 *
 *  - Sends {"type":"track",...} on every track change (with seq).
 *  - Receives {"type":"artwork",...} JSON header followed by ONE binary
 *    frame containing the JPEG.
 *  - Receives {"type":"gps",...} at ~1 Hz.
 *  - Keepalive ping/pong every 10 s; reconnects with capped backoff.
 *
 * When the iPhone is the hotspot, it is the default gateway: 172.20.10.1.
 */
class PhoneLinkClient(
    private val serverUrl: String = "ws://172.20.10.1:8777",
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        /** Artwork for the track with matching [seq]; null bitmap = not found, show placeholder. */
        fun onArtwork(seq: Int, bitmap: Bitmap?, source: String?)
        fun onGps(lat: Double, lon: Double, speedMps: Double, headingDeg: Double, altitudeM: Double, hAccM: Double)
    }

    private companion object {
        const val TAG = "PhoneLinkClient"
        const val PING_INTERVAL_MS = 10_000L
        const val MAX_BACKOFF_MS = 5_000L
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS) // transport-level keepalive
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val seqCounter = AtomicInteger(0)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    private var backoffMs = 500L

    /** Header of the artwork whose binary frame we are waiting for. */
    @Volatile private var pendingArtwork: JSONObject? = null
    /** Re-sent after every reconnect so the iPhone can push artwork again. */
    @Volatile private var lastTrackJson: JSONObject? = null
    @Volatile var currentSeq: Int = -1
        private set

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        ws?.close(1000, "bye")
        ws = null
    }

    /** Call from BtMetadataListener on every track change. Returns the seq assigned. */
    fun sendTrack(title: String, artist: String, album: String?, durationMs: Long): Int {
        val seq = seqCounter.incrementAndGet()
        currentSeq = seq
        val json = JSONObject()
            .put("type", "track")
            .put("seq", seq)
            .put("title", title)
            .put("artist", artist)
            .put("album", album ?: JSONObject.NULL)
            .put("durationMs", durationMs)
        lastTrackJson = json
        ws?.send(json.toString())
        return seq
    }

    private fun connect() {
        if (!running) return
        val request = Request.Builder().url(serverUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $serverUrl")
                ws = webSocket
                backoffMs = 500L
                pendingArtwork = null
                // Re-announce the current track so artwork resumes after reconnect.
                lastTrackJson?.let { webSocket.send(it.toString()) }
                handler.post { listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleJson(JSONObject(text), webSocket)
                } catch (e: Exception) {
                    Log.w(TAG, "Bad JSON frame: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val header = pendingArtwork ?: run {
                    Log.w(TAG, "Unexpected binary frame (${bytes.size} bytes), dropping")
                    return
                }
                pendingArtwork = null
                val seq = header.optInt("seq", -1)
                if (seq != currentSeq) {
                    Log.i(TAG, "Stale artwork for seq=$seq (current=$currentSeq), dropping")
                    return
                }
                val raw = bytes.toByteArray()
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                handler.post { listener.onArtwork(seq, bmp, header.optString("source", null)) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Socket failure: ${t.message}")
                ws = null
                handler.post { listener.onDisconnected() }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                handler.post { listener.onDisconnected() }
                scheduleReconnect()
            }
        })
    }

    private fun handleJson(msg: JSONObject, webSocket: WebSocket) {
        when (msg.optString("type")) {
            "gps" -> handler.post {
                listener.onGps(
                    msg.getDouble("lat"),
                    msg.getDouble("lon"),
                    msg.optDouble("speedMps", -1.0),
                    msg.optDouble("headingDeg", -1.0),
                    msg.optDouble("altitudeM", 0.0),
                    msg.optDouble("hAccM", -1.0)
                )
            }
            "artwork" -> {
                if (msg.optString("status") == "ok") {
                    pendingArtwork = msg // JPEG arrives in the next binary frame
                } else {
                    val seq = msg.optInt("seq", -1)
                    if (seq == currentSeq) {
                        handler.post { listener.onArtwork(seq, null, null) }
                    }
                }
            }
            "ping" -> webSocket.send("""{"type":"pong"}""")
            "pong" -> { /* keepalive ack */ }
            else -> Log.w(TAG, "Unknown message type: $msg")
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        Log.i(TAG, "Reconnecting in ${backoffMs}ms")
        handler.postDelayed({ connect() }, backoffMs)
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
    }
}
