package org.chronoscompanion.app.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import java.util.ArrayDeque
import java.util.UUID
import org.chronoscompanion.app.notification.ChronosNotificationListener
import org.chronoscompanion.app.weather.WeatherFetcher

/**
 * Foreground service owning the single BLE connection to the watch (ChronosESP32
 * firmware). Scans for a device advertising [ChronosProtocol.SERVICE_UUID], connects,
 * and exposes send* methods that build + chunk + queue packets over the RX
 * characteristic. Writes are sent one at a time, waiting for each
 * onCharacteristicWrite callback before sending the next chunk, since the firmware's
 * chunk-reassembly relies on receiving them in order.
 */
class ChronosBleService : Service() {

    enum class Status { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    /** A BLE device advertising the Chronos UART service, found during [startDeviceDiscovery]. */
    data class DiscoveredDevice(val name: String, val address: String)

    companion object {
        private const val TAG = "ChronosBleService"
        private const val CHANNEL_ID = "chronos_ble"
        private const val NOTIF_ID = 1
        private const val TIME_SYNC_INTERVAL_MS = 10 * 60 * 1000L // re-sync every 10 min to correct drift
        private const val WEATHER_REFRESH_INTERVAL_MS = 30 * 60 * 1000L // Open-Meteo updates hourly at most
        private const val DISCOVERY_DURATION_MS = 8000L

        const val ACTION_STATUS_CHANGED = "org.chronoscompanion.app.STATUS_CHANGED"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val ACTION_STOP = "org.chronoscompanion.app.ACTION_STOP"

        const val PREFS_NAME = "chronos_prefs"
        const val PREF_DEVICE_NAME = "device_name"
        private const val PREF_DEVICE_ADDRESS = "device_address"

        @Volatile
        var instance: ChronosBleService? = null
            private set

        @Volatile
        var status: Status = Status.DISCONNECTED
            private set

        /** Set by MainActivity while a device-picker dialog is open; cleared on dismiss/destroy. */
        @Volatile
        var discoveryCallback: ((List<DiscoveredDevice>) -> Unit)? = null
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var writeGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from notification action")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground()
        // The service is a singleton within the process: once running, every extra
        // startForegroundService() call (each tap of "Conectar", or the system
        // restarting a START_STICKY service) re-enters here. Only kick off a scan if
        // we're not already scanning/connecting/connected - otherwise each call used
        // to register a brand new BLE scanner (see startScan()'s old bug) without ever
        // stopping the previous one, leaking scanner registrations until the phone's
        // BLE stack ran out of scan slots and stopped working entirely.
        if (status == Status.DISCONNECTED) {
            startScan()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTimeSyncTimer()
        stopWeatherTimer()
        stopScanIfRunning()
        bluetoothGatt?.close()
        bluetoothGatt = null
        instance = null
        setStatus(Status.DISCONNECTED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setStatus(s: Status) {
        status = s
        val i = Intent(ACTION_STATUS_CHANGED)
        i.putExtra(EXTRA_STATUS, s.name)
        i.putExtra(EXTRA_DEVICE_NAME, prefs().getString(PREF_DEVICE_NAME, null))
        sendBroadcast(i)
        updateNotification(s)
    }

    // ---------------------------------------------------------------------
    // Remembered device (which specific watch to reconnect to)
    // ---------------------------------------------------------------------

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun savedAddress(): String? = prefs().getString(PREF_DEVICE_ADDRESS, null)

    private fun saveDevice(address: String, name: String) {
        prefs().edit().putString(PREF_DEVICE_ADDRESS, address).putString(PREF_DEVICE_NAME, name).apply()
    }

    /** Disconnects and clears the remembered device, so the next scan won't auto-pick one -
     * the user has to choose again via [startDeviceDiscovery]. */
    fun forgetDevice() {
        Log.i(TAG, "Forgetting saved device")
        prefs().edit().remove(PREF_DEVICE_ADDRESS).remove(PREF_DEVICE_NAME).apply()
        stopScanIfRunning()
        stopTimeSyncTimer()
        stopWeatherTimer()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        writeQueue.clear()
        writeInFlight = false
        setStatus(Status.DISCONNECTED)
    }

    // ---------------------------------------------------------------------
    // Scanning / connecting
    // ---------------------------------------------------------------------

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            // adapter.bluetoothLeScanner returns null when Bluetooth is off or the
            // stack is mid-restart. This used to just bail out here forever - status
            // stayed on DISCONNECTED/"Buscando el reloj..." with nothing left to ever
            // retry, so a transient BT hiccup meant the app never reconnected again
            // until manually force-stopped. Retry instead of giving up.
            Log.w(TAG, "startScan: BluetoothLeScanner unavailable, retrying in 3s")
            setStatus(Status.DISCONNECTED)
            mainHandler.postDelayed({ startScan() }, 3000)
            return
        }
        // Always stop whatever scan (if any) is still registered under the current
        // scanCallback before registering a new one - startScan() used to overwrite
        // scanCallback unconditionally, orphaning the previous scanner registration.
        stopScanIfRunning()
        setStatus(Status.SCANNING)

        // If the user has already picked/connected to a specific watch before, only
        // reconnect to THAT one (by MAC) - otherwise, with several Chronos boards
        // nearby, a plain reconnect scan could latch onto a different board than the
        // one the user chose via startDeviceDiscovery().
        val address = savedAddress()
        val filterBuilder = ScanFilter.Builder().setServiceUuid(ParcelUuid(ChronosProtocol.SERVICE_UUID))
        if (address != null) {
            filterBuilder.setDeviceAddress(address)
        }
        val filter = filterBuilder.build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(TAG, "Found device: ${result.device.address} (${result.device.name})")
                stopScanIfRunning()
                if (address == null) {
                    // First-time connect with nothing remembered yet - adopt whatever
                    // Chronos-compatible device answered first, same as before this
                    // feature existed. From now on reconnects target this specific one.
                    saveDevice(result.device.address, result.device.name ?: "Reloj Chronos")
                }
                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                setStatus(Status.DISCONNECTED)
            }
        }
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanIfRunning() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }

    /**
     * Scans for [DISCOVERY_DURATION_MS] collecting every nearby device advertising the
     * Chronos UART service (not just the remembered one), for the "Cambiar de reloj"
     * picker. Interrupts whatever scan was running and resumes normal reconnect
     * scanning afterwards if still disconnected.
     */
    @SuppressLint("MissingPermission")
    fun startDeviceDiscovery(onResult: (List<DiscoveredDevice>) -> Unit) {
        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            onResult(emptyList())
            return
        }
        stopScanIfRunning()
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ChronosProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "Reloj Chronos"
                found[result.device.address] = DiscoveredDevice(name, result.device.address)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Discovery scan failed: $errorCode")
            }
        }
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
        mainHandler.postDelayed({
            stopScanIfRunning()
            onResult(found.values.toList())
            if (status != Status.CONNECTED) {
                startScan()
            }
        }, DISCOVERY_DURATION_MS)
    }

    /** Connects to a device picked from [startDeviceDiscovery]'s list, replacing whatever was remembered before. */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: DiscoveredDevice) {
        Log.i(TAG, "Switching to device ${device.name} (${device.address})")
        stopScanIfRunning()
        stopTimeSyncTimer()
        stopWeatherTimer()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        writeQueue.clear()
        writeInFlight = false
        saveDevice(device.address, device.name)
        val adapter = bluetoothAdapter ?: return
        connect(adapter.getRemoteDevice(device.address))
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        setStatus(Status.CONNECTING)
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "GATT disconnected (status=$status)")
                setStatus(Status.DISCONNECTED)
                rxCharacteristic = null
                writeQueue.clear()
                writeInFlight = false
                stopTimeSyncTimer()
                stopWeatherTimer()
                mainHandler.postDelayed({ startScan() }, 3000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(ChronosProtocol.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Chronos UART service not found on this device")
                gatt.disconnect()
                return
            }
            rxCharacteristic = service.getCharacteristic(ChronosProtocol.CHARACTERISTIC_UUID_RX)
            val txCharacteristic = service.getCharacteristic(ChronosProtocol.CHARACTERISTIC_UUID_TX)

            if (txCharacteristic != null) {
                gatt.setCharacteristicNotification(txCharacteristic, true)
                val cccd = txCharacteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
            }

            setStatus(Status.CONNECTED)
            Log.i(TAG, "Chronos UART service ready")
            sendTimeSync()
            startTimeSyncTimer()
            startWeatherTimer()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight = false
            writeGeneration++ // invalidates any pending timeout scheduled for this write
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: status=$status")
            } else {
                Log.i(TAG, "Write OK (${writeQueue.size} chunk(s) still queued)")
            }
            drainQueue()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "TX notify: ${value.joinToString(" ") { "%02x".format(it) }}")
            ChronosProtocol.decodeWatchCommand(value)?.let { handleWatchCommand(it) }
        }
    }

    // ---------------------------------------------------------------------
    // Music/volume control commands from the watch
    // ---------------------------------------------------------------------

    private fun handleWatchCommand(command: ChronosProtocol.WatchCommand) {
        Log.i(TAG, "Watch command: $command")
        when (command) {
            ChronosProtocol.WatchCommand.MUSIC_PLAY -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            ChronosProtocol.WatchCommand.MUSIC_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ChronosProtocol.WatchCommand.MUSIC_TOGGLE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ChronosProtocol.WatchCommand.MUSIC_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            ChronosProtocol.WatchCommand.MUSIC_PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ChronosProtocol.WatchCommand.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            ChronosProtocol.WatchCommand.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            ChronosProtocol.WatchCommand.VOLUME_MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
        }
    }

    /**
     * Dispatches a media button press to the currently playing app's MediaSession, the
     * same way a physical headset button would - works across apps (Spotify, YouTube
     * Music, etc.) without needing to track play/pause state ourselves. Requires
     * notification listener access, which [ChronosNotificationListener] already holds.
     */
    private fun sendMediaKey(keyCode: Int) {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers: List<MediaController> = try {
            sessionManager.getActiveSessions(ComponentName(this, ChronosNotificationListener::class.java))
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification listener access, cannot control media: ${e.message}")
            return
        }
        val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        if (controller == null) {
            Log.w(TAG, "No active media session to control")
            return
        }
        val eventTime = SystemClock.uptimeMillis()
        controller.dispatchMediaButtonEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        controller.dispatchMediaButtonEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun drainQueue() {
        if (writeInFlight) return
        if (writeQueue.isEmpty()) return
        val gatt = bluetoothGatt
        val characteristic = rxCharacteristic
        if (gatt == null || characteristic == null) {
            Log.w(TAG, "drainQueue: no GATT/characteristic yet, ${writeQueue.size} packet(s) waiting")
            return
        }
        val next = writeQueue.poll() ?: return
        writeInFlight = true
        val myGeneration = ++writeGeneration
        Log.i(TAG, "Writing ${next.size} bytes: ${next.joinToString(" ") { "%02x".format(it) }}")
        // onCharacteristicWrite occasionally never arrives after a stop/reconnect cycle
        // (observed: BLE stays "connected" but the watch's write ack callback is lost),
        // which used to leave writeInFlight stuck true forever - every future packet
        // (weather, navigation, everything) then got silently queued and never sent,
        // with no error logged. Force it back open after a timeout so the queue keeps
        // moving; the generation check no-ops this if the real callback still lands late.
        mainHandler.postDelayed({
            if (writeInFlight && writeGeneration == myGeneration) {
                Log.w(TAG, "Write callback timed out, resetting queue")
                writeInFlight = false
                drainQueue()
            }
        }, 5000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                next,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun enqueuePacket(logicalPacket: ByteArray) {
        val chunks = ChronosProtocol.chunk(logicalPacket)
        writeQueue.addAll(chunks)
        mainHandler.post { drainQueue() }
    }

    fun sendNavigationData(
        title: String,
        duration: String,
        distance: String,
        eta: String,
        directions: String,
        speed: String,
        hasIcon: Boolean,
        iconCrc: Long
    ) {
        val packet = ChronosProtocol.buildNavigationData(
            title = title,
            duration = duration,
            distance = distance,
            eta = eta,
            directions = directions,
            speed = speed,
            hasIcon = hasIcon,
            isNavigation = true,
            iconCrc = iconCrc
        )
        enqueuePacket(packet)
    }

    fun sendNavigationStop() {
        enqueuePacket(ChronosProtocol.buildNavigationStop())
    }

    fun sendNotification(iconCode: Int, message: String) {
        enqueuePacket(ChronosProtocol.buildNotification(iconCode, message))
    }

    private val timeSyncRunnable = object : Runnable {
        override fun run() {
            sendTimeSync()
            mainHandler.postDelayed(this, TIME_SYNC_INTERVAL_MS)
        }
    }

    private fun sendTimeSync() {
        Log.i(TAG, "Sending time sync")
        enqueuePacket(ChronosProtocol.buildSetTime(java.util.Calendar.getInstance()))
    }

    private fun startTimeSyncTimer() {
        mainHandler.removeCallbacks(timeSyncRunnable)
        mainHandler.postDelayed(timeSyncRunnable, TIME_SYNC_INTERVAL_MS)
    }

    private fun stopTimeSyncTimer() {
        mainHandler.removeCallbacks(timeSyncRunnable)
    }

    private val weatherRunnable = object : Runnable {
        override fun run() {
            sendWeather()
            mainHandler.postDelayed(this, WEATHER_REFRESH_INTERVAL_MS)
        }
    }

    /** Fetches from Open-Meteo on a background thread (network I/O), then sends the
     * resulting packets back on the main handler like every other BLE write. */
    private fun sendWeather() {
        Thread {
            val location = WeatherFetcher.getLastLocation(this)
            if (location == null) {
                Log.w(TAG, "sendWeather: no last known location available")
                return@Thread
            }
            val result = WeatherFetcher.fetch(this, location)
            if (result == null) {
                Log.w(TAG, "sendWeather: fetch failed")
                return@Thread
            }
            mainHandler.post {
                Log.i(TAG, "Sending weather: ${result.city}, today ${result.days.firstOrNull()?.temp}°")
                enqueuePacket(ChronosProtocol.buildWeatherDaily(result.days))
                enqueuePacket(ChronosProtocol.buildWeatherHighLow(result.days))
                enqueuePacket(ChronosProtocol.buildWeatherUvPressure(result.uvIndex, result.pressureHpa))
                if (result.city.isNotEmpty()) {
                    enqueuePacket(ChronosProtocol.buildWeatherCity(result.city))
                }
            }
        }.start()
    }

    private fun startWeatherTimer() {
        mainHandler.removeCallbacks(weatherRunnable)
        mainHandler.postDelayed(weatherRunnable, 2000)
    }

    private fun stopWeatherTimer() {
        mainHandler.removeCallbacks(weatherRunnable)
    }

    /** [packedIcon] must be the 288-byte buffer from [ChronosProtocol.packIconBits]. */
    fun sendNavigationIcon(packedIcon: ByteArray, crc: Long) {
        for (chunkPos in 0..2) {
            val chunkBytes = packedIcon.copyOfRange(chunkPos * 96, chunkPos * 96 + 96)
            enqueuePacket(ChronosProtocol.buildNavigationIconChunk(chunkPos, crc, chunkBytes))
        }
    }

    // ---------------------------------------------------------------------
    // Foreground notification (required to keep a BLE connection + notification
    // listener alive reliably while the app is backgrounded)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Conexión con el reloj", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun startForeground() {
        val notification = buildNotification(Status.DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(s: Status) {
        val notification = buildNotification(s)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    private fun buildNotification(s: Status): Notification {
        val text = when (s) {
            Status.DISCONNECTED -> "Buscando el reloj…"
            Status.SCANNING -> "Buscando el reloj…"
            Status.CONNECTING -> "Conectando con el reloj…"
            Status.CONNECTED -> "Conectado al reloj"
        }
        val stopIntent = Intent(this, ChronosBleService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Detener",
            stopPendingIntent
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Chronos Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }
}
