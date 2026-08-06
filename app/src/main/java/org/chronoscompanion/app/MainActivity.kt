package org.chronoscompanion.app

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.chronoscompanion.app.ble.ChronosBleService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceNameText: TextView
    private lateinit var statusDot: View

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val statusName = intent.getStringExtra(ChronosBleService.EXTRA_STATUS) ?: return
            applyStatus(ChronosBleService.Status.valueOf(statusName))
            applyDeviceName(intent.getStringExtra(ChronosBleService.EXTRA_DEVICE_NAME))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleService()
        } else {
            statusText.text = getString(R.string.permissions_missing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceNameText = findViewById(R.id.deviceNameText)
        statusDot = findViewById(R.id.statusDot)

        findViewById<MaterialButton>(R.id.notifAccessButton).setOnClickListener {
            openNotificationAccessSettings()
        }
        findViewById<MaterialButton>(R.id.connectButton).setOnClickListener {
            requestPermissionsAndStart()
        }
        findViewById<MaterialButton>(R.id.changeDeviceButton).setOnClickListener {
            changeDevice()
        }
        findViewById<MaterialButton>(R.id.forgetDeviceButton).setOnClickListener {
            confirmForgetDevice()
        }

        val filter = IntentFilter(ChronosBleService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        // The service keeps running (and stays connected) across the activity being
        // closed and reopened - it only reacts to NEW state-change broadcasts, so
        // reopening the app showed the hardcoded "sin iniciar" text forever even while
        // already connected. Read the service's current state directly on resume.
        //
        // ChronosBleService.status is a companion-object var that defaults to
        // DISCONNECTED and is never reset - if the OS killed the whole app process
        // (this phone's battery manager does that aggressively) and the service never
        // got a chance to restart, that stale default reads as "Buscando el reloj..."
        // even though nothing is actually running, which is misleading (and confusing
        // together with "Cambiar de reloj" correctly refusing to work). Show a distinct
        // "stopped" state instead whenever there's no live service instance.
        if (ChronosBleService.instance == null) {
            statusText.text = getString(R.string.status_prefix) + "Detenido"
            (statusDot.background as GradientDrawable).setColor(ContextCompat.getColor(this, R.color.status_disconnected))
        } else {
            applyStatus(ChronosBleService.status)
        }
        val savedName = getSharedPreferences(ChronosBleService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ChronosBleService.PREF_DEVICE_NAME, null)
        applyDeviceName(savedName)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        ChronosBleService.discoveryCallback = null
        super.onDestroy()
    }

    private fun applyStatus(status: ChronosBleService.Status) {
        val (label, colorRes) = when (status) {
            ChronosBleService.Status.DISCONNECTED -> getString(R.string.status_prefix) + "Buscando el reloj…" to R.color.status_disconnected
            ChronosBleService.Status.SCANNING -> getString(R.string.status_prefix) + "Buscando el reloj…" to R.color.status_connecting
            ChronosBleService.Status.CONNECTING -> getString(R.string.status_prefix) + "Conectando…" to R.color.status_connecting
            ChronosBleService.Status.CONNECTED -> getString(R.string.status_prefix) + "Conectado" to R.color.status_connected
        }
        statusText.text = label
        (statusDot.background as GradientDrawable).setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun applyDeviceName(name: String?) {
        if (name.isNullOrEmpty()) {
            deviceNameText.visibility = View.GONE
        } else {
            deviceNameText.text = name
            deviceNameText.visibility = View.VISIBLE
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Needed to fetch weather for the phone's location (WeatherFetcher), not BLE-related.
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBleService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBleService() {
        val intent = Intent(this, ChronosBleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        statusText.text = getString(R.string.status_starting)
    }

    // -------------------------------------------------------------------
    // Device picker / switching
    // -------------------------------------------------------------------

    private fun changeDevice() {
        val service = ChronosBleService.instance
        if (service == null) {
            Toast.makeText(this, "Primero conectate una vez con el botón 2", Toast.LENGTH_SHORT).show()
            return
        }
        val progressBar = android.widget.ProgressBar(this).apply {
            setPadding(0, 32, 0, 0)
        }
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.discovery_searching)
            .setView(progressBar)
            .setCancelable(false)
            .show()
        service.startDeviceDiscovery { devices ->
            runOnUiThread {
                progress.dismiss()
                showDevicePicker(devices)
            }
        }
    }

    private fun showDevicePicker(devices: List<ChronosBleService.DiscoveredDevice>) {
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.discovery_none_found, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.discovery_title)
            .setItems(labels) { _, which ->
                ChronosBleService.instance?.connectToDevice(devices[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmForgetDevice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.button_forget_device)
            .setMessage("Se cortará la conexión actual y vas a tener que elegir un reloj de nuevo.")
            .setPositiveButton(R.string.button_forget_device) { _, _ ->
                ChronosBleService.instance?.forgetDevice()
                applyDeviceName(null)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
