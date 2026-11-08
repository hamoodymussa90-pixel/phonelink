package com.example.headunitlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE transport (protocol v1 over GATT) — alternative/fallback to the Wi-Fi
 * WebSocket [PhoneLinkClient]. The iPhone advertises the PhoneLink service;
 * this client scans, connects, subscribes, and reassembles chunked artwork.
 *
 * Reuses [PhoneLinkClient.Listener] so the dashboard code is transport-agnostic.
 *
 * Permissions (API 31+): BLUETOOTH_SCAN, BLUETOOTH_CONNECT.
 * Pre-31: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION.
 *
 * iOS caveat: when the PhoneLink app is backgrounded, iOS stops advertising
 * the service UUID in a scannable way — connect while the app is foregrounded
 * once; the established connection survives the iPhone locking.
 */
@SuppressLint("MissingPermission")
class BleLinkClient(
    private val context: Context,
    private val listener: PhoneLinkClient.Listener
) {
    private companion object {
        const val TAG = "BleLinkClient"
        val SERVICE: UUID = UUID.fromString("7E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val GPS: UUID = UUID.fromString("7E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TRACK: UUID = UUID.fromString("7E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val ART_META: UUID = UUID.fromString("7E400004-B5A3-F393-E0A9-E50E24DCCA9E")
        val ART_DATA: UUID = UUID.fromString("7E400005-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val RETRY_MS = 3_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val seqCounter = AtomicInteger(0)

    @Volatile private var running = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready = false

    /** CCCD descriptor writes must be serialized — simple op queue. */
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // Artwork reassembly state
    private var artSeq = -1
    private var artExpectedBytes = 0
    private var artExpectedChunks = 0
    private var artNextIndex = 0
    private var artWriteOffset = 0
    private var artBuffer: ByteArray = ByteArray(0)
    private var artSource: String? = null

    @Volatile private var pendingTrackJson: String? = null
    @Volatile var currentSeq: Int = -1
        private set

    fun start() {
        if (running) return
        running = true
        scan()
    }

    fun stop() {
        running = false
        btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.close()
        gatt = null
        ready = false
    }

    /** Call on every track change (same contract as PhoneLinkClient.sendTrack). */
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
            .toString()
        pendingTrackJson = json
        if (ready) writeTrack(json)
        return seq
    }

    // MARK: scanning / connecting

    private fun scan() {
        if (!running) return
        val scanner = btManager.adapter?.bluetoothLeScanner ?: run {
            handler.postDelayed({ scan() }, RETRY_MS); return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        Log.i(TAG, "Scanning for PhoneLink service…")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            btManager.adapter.bluetoothLeScanner.stopScan(this)
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            handler.postDelayed({ scan() }, RETRY_MS)
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!running) return
        Log.i(TAG, "Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reconnect() {
        ready = false
        gatt?.close()
        gatt = null
        handler.post { listener.onDisconnected() }
        if (running) handler.postDelayed({ scan() }, RETRY_MS)
    }

    // MARK: GATT

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                reconnect()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE) ?: run { reconnect(); return }
            subscribeQueue.clear()
            listOf(GPS, ART_META, ART_DATA).forEach { uuid ->
                service.getCharacteristic(uuid)?.let { subscribeQueue.addLast(it) }
            }
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            subscribeNext(g)
        }

        @Deprecated("Deprecated in API 33, kept for broad compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                GPS -> handleGps(value)
                ART_META -> handleArtMeta(value)
                ART_DATA -> handleArtChunk(value)
            }
        }
    }

    private fun subscribeNext(g: BluetoothGatt) {
        val characteristic = subscribeQueue.removeFirstOrNull() ?: run {
            // All subscriptions done — link is live.
            ready = true
            handler.post { listener.onConnected() }
            pendingTrackJson?.let { writeTrack(it) }
            return
        }
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD) ?: run { subscribeNext(g); return }
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        g.writeDescriptor(cccd)
    }

    private fun writeTrack(json: String) {
        val g = gatt ?: return
        val characteristic = g.getService(SERVICE)?.getCharacteristic(TRACK) ?: return
        @Suppress("DEPRECATION")
        characteristic.value = json.toByteArray()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        g.writeCharacteristic(characteristic)
    }

    // MARK: message handling

    private fun handleGps(value: ByteArray) {
        try {
            val msg = JSONObject(String(value))
            if (msg.optString("type") != "gps") return
            handler.post {
                listener.onGps(
                    msg.getDouble("lat"),
                    msg.getDouble("lon"),
                    msg.optDouble("speedMps", -1.0),
                    msg.optDouble("headingDeg", -1.0),
                    msg.optDouble("altitudeM", 0.0),
                    msg.optDouble("hAccM", -1.0)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad gps frame: ${e.message}")
        }
    }

    private fun handleArtMeta(value: ByteArray) {
        try {
            val meta = JSONObject(String(value))
            val seq = meta.optInt("seq", -1)
            if (meta.optString("status") != "ok") {
                if (seq == currentSeq) handler.post { listener.onArtwork(seq, null, null) }
                return
            }
            artSeq = seq
            artExpectedBytes = meta.getInt("bytes")
            artExpectedChunks = meta.getInt("chunks")
            artNextIndex = 0
            artWriteOffset = 0
            artBuffer = ByteArray(artExpectedBytes)
            artSource = meta.optString("source", "online")
        } catch (e: Exception) {
            Log.w(TAG, "Bad artwork meta: ${e.message}")
        }
    }

    private fun handleArtChunk(value: ByteArray) {
        if (value.size < 5 || artExpectedChunks == 0) return
        val seq = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        val index = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        if (seq != (artSeq and 0xFFFF)) return
        if (index != artNextIndex) {
            // ATT notifications are ordered; a gap means we lost the transfer.
            Log.w(TAG, "Artwork chunk gap (expected $artNextIndex, got $index), aborting")
            artExpectedChunks = 0
            return
        }

        val payloadSize = value.size - 4
        if (artWriteOffset + payloadSize > artBuffer.size) {
            artExpectedChunks = 0
            return
        }
        System.arraycopy(value, 4, artBuffer, artWriteOffset, payloadSize)
        artWriteOffset += payloadSize
        artNextIndex++

        if (artNextIndex >= artExpectedChunks) {
            artExpectedChunks = 0
            if (artWriteOffset != artExpectedBytes) {
                Log.w(TAG, "Artwork size mismatch ($artWriteOffset/$artExpectedBytes)")
                return
            }
            if (artSeq != currentSeq) {
                Log.i(TAG, "Stale BLE artwork for seq=$artSeq, dropping")
                return
            }
            val bmp = BitmapFactory.decodeByteArray(artBuffer, 0, artBuffer.size)
            val source = artSource
            handler.post { listener.onArtwork(artSeq, bmp, source) }
        }
    }
}
