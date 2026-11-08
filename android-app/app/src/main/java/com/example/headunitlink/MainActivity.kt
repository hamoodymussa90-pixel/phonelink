package com.example.headunitlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), DashboardBus.Observer {

    private lateinit var statusText: TextView
    private lateinit var artworkView: ImageView
    private lateinit var trackText: TextView
    private lateinit var speedText: TextView
    private lateinit var gpsText: TextView
    private lateinit var urlInput: EditText
    private lateinit var bleSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        artworkView = findViewById(R.id.artworkView)
        trackText = findViewById(R.id.trackText)
        speedText = findViewById(R.id.speedText)
        gpsText = findViewById(R.id.gpsText)
        urlInput = findViewById(R.id.urlInput)
        bleSwitch = findViewById(R.id.bleSwitch)

        val prefs = getSharedPreferences(LinkForegroundService.PREFS, MODE_PRIVATE)
        urlInput.setText(prefs.getString(LinkForegroundService.KEY_URL, LinkForegroundService.DEFAULT_URL))
        bleSwitch.isChecked = prefs.getBoolean(LinkForegroundService.KEY_BLE, false)

        findViewById<Button>(R.id.applyBtn).setOnClickListener {
            prefs.edit()
                .putString(LinkForegroundService.KEY_URL, urlInput.text.toString().trim())
                .putBoolean(LinkForegroundService.KEY_BLE, bleSwitch.isChecked)
                .apply()
            restartLink()
        }
        findViewById<Button>(R.id.notifBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        requestNeededPermissions()
        startLink()
    }

    override fun onResume() {
        super.onResume()
        DashboardBus.observer = this
    }

    override fun onPause() {
        super.onPause()
        DashboardBus.observer = null
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        restartLink() // retry transports now that permissions may be granted
    }

    private fun startLink() {
        startForegroundService(Intent(this, LinkForegroundService::class.java))
    }

    private fun restartLink() {
        stopService(Intent(this, LinkForegroundService::class.java))
        startLink()
    }

    // ---- DashboardBus.Observer ----

    override fun onTrackChanged(title: String, artist: String, album: String?) = runOnUiThread {
        trackText.text = if (artist.isBlank()) title else "$artist — $title"
    }

    override fun onArtworkChanged(bitmap: Bitmap?, source: String) = runOnUiThread {
        if (bitmap != null) artworkView.setImageBitmap(bitmap)
        else artworkView.setImageResource(android.R.drawable.ic_menu_gallery)
    }

    override fun onGpsChanged(lat: Double, lon: Double, smoothedSpeedMps: Double, headingDeg: Double) = runOnUiThread {
        speedText.text =
            if (smoothedSpeedMps >= 0) "${(smoothedSpeedMps * 3.6).toInt()} km/h" else "--"
        gpsText.text = String.format("%.5f, %.5f   hdg %.0f°", lat, lon, headingDeg)
    }

    override fun onLinkStateChanged(connected: Boolean) = runOnUiThread {
        statusText.text = if (connected) "✅ Connected to iPhone" else "⏳ Disconnected — retrying…"
    }
}
