
package com.sudesh.nowplaying
import android.util.Log
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.session.PlaybackState
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.ListView
import android.graphics.Bitmap
import android.os.SystemClock

import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 1001
    }

    private lateinit var artworkView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private lateinit var playPauseButton: Button
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button
    
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var deviceListView: ListView
    private var deviceDialog: AlertDialog? = null

    private data class ScannedDevice(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int
    )

    private val scannedDevices = mutableListOf<ScannedDevice>()

    private lateinit var bleManager: BLEManager

    private var currentDuration = 0L
    private var currentPosition = 0L
    private var isPlaying = false
    private var esp32Connected = false

    // Playback position reference for local progress interpolation
    private var positionAnchor = 0L
    private var positionAnchorTime = 0L

    // Track and artwork change detection
    private var lastTrackKey: String? = null
    private var lastArtworkHash: Int? = null

    // BLE track identity
    private var currentTrackId = 1
    private var nextTrackId = 2

    private val handler = Handler(Looper.getMainLooper())

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI(MediaListenerService.currentSnapshot)
        }
    }

    private val bleListener = object : BLEManager.BLEListener {

        
        override fun onScanDevice(
            device: BluetoothDevice,
            name: String?,
            rssi: Int
        ) {
            runOnUiThread {
                val address = device.address
                val displayName = name?.takeIf { it.isNotBlank() }
                    ?: "Unknown device"

                val existingIndex = scannedDevices.indexOfFirst {
                    it.device.address == address
                }

                val scannedDevice = ScannedDevice(
                    device,
                    displayName,
                    rssi
                )

                val row = "$displayName\n$address  •  $rssi dBm"

                if (existingIndex == -1) {
                    // New device
                    scannedDevices.add(scannedDevice)
                    deviceAdapter.add(row)
                } else {
                    // Already discovered: update its name/RSSI
                    scannedDevices[existingIndex] = scannedDevice

                    val oldRow = deviceAdapter.getItem(existingIndex)
                    if (oldRow != null) {
                        deviceAdapter.remove(oldRow)
                        deviceAdapter.insert(row, existingIndex)
                    }
                }

                deviceDialog?.setTitle(
                    "Available Bluetooth Devices (${scannedDevices.size})"
                )
            }
        }

        override fun onConnected() {
            runOnUiThread {
                esp32Connected = true

                // Force artwork to be sent again after reconnecting.
                lastArtworkHash = null

                statusView.text = "ESP32 connected successfully!"
                connectButton.text = "Disconnect ESP32"
                connectButton.isEnabled = true

                // Send the latest complete snapshot.
                updateUI(MediaListenerService.currentSnapshot)
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                esp32Connected = false
                statusView.text = "ESP32 disconnected"
                connectButton.text = "Scan for devices"
                connectButton.isEnabled = true
            }
        }

        override fun onCommand(command: String) {
            runOnUiThread {
                val controller = MediaListenerService.activeController
                    ?: return@runOnUiThread

                when {
                    command == "PREV" -> {
                        controller.transportControls.skipToPrevious()
                    }

                    command == "NEXT" -> {
                        controller.transportControls.skipToNext()
                    }

                    command == "PLAY" -> {
                        if (controller.playbackState?.state ==
                            PlaybackState.STATE_PLAYING
                        ) {
                            controller.transportControls.pause()
                        } else {
                            controller.transportControls.play()
                        }
                    }

                    command.startsWith("SEEK:") -> {
                        val position = command.substringAfter("SEEK:")
                            .toLongOrNull()

                        position?.let {
                            controller.transportControls.seekTo(
                                it.coerceAtLeast(0L)
                            )
                        }
                    }

                    else -> {
                        Log.w("MainActivity", "Unknown ESP32 command: $command")
                    }
                }
            }
        }

        override fun onError(message: String) {
        runOnUiThread {
            statusView.text = message
            deviceDialog?.setTitle(message)
            connectButton.isEnabled = true
        }
    }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {

            if (isPlaying && currentDuration > 0L) {

                val elapsed =
                    (SystemClock.elapsedRealtime() - positionAnchorTime)
                        .coerceAtLeast(0L)

                currentPosition = (positionAnchor + elapsed)
                    .coerceIn(0L, currentDuration)

            } else {
                currentPosition = positionAnchor
                    .coerceIn(0L, currentDuration)
            }

            updateProgress()

            // ESP32 performs its own local progress interpolation.
            // Do not send a full metadata transfer every second.

            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BLEManager(this, bleListener)

        createUI()
    }

    override fun onResume() {
        super.onResume()

        registerReceiver(
            updateReceiver,
            IntentFilter(MediaListenerService.ACTION_MEDIA_UPDATED),
            Context.RECEIVER_NOT_EXPORTED
        )

        updateUI(MediaListenerService.currentSnapshot)

        handler.post(progressRunnable)
    }

    override fun onPause() {
        super.onPause()

        try {
            unregisterReceiver(updateReceiver)
        } catch (_: IllegalArgumentException) {
        }

        handler.removeCallbacks(progressRunnable)
    }

    override fun onDestroy() {
        bleManager.disconnect()
        super.onDestroy()
    }

    // --------------------------------------------------
    // BLUETOOTH PERMISSIONS
    // --------------------------------------------------

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                REQUEST_BLE_PERMISSIONS
            )

        } else {
            startBleScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_BLE_PERMISSIONS) {

            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {
                startBleScan()
            } else {
                statusView.text = "Bluetooth permissions denied"
                connectButton.isEnabled = true
            }
        }
    }

    // --------------------------------------------------
    // BLE CONNECTION
    // --------------------------------------------------

    private fun startBleScan() {

        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }

        scannedDevices.clear()

        statusView.text = "Scanning for BLE devices..."

        showDeviceDialog()

        bleManager.startScan()
    }

    private fun handleConnectButton() {

        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }

        if (connectButton.text == "Disconnect ESP32") {

            bleManager.disconnect()

            connectButton.text = "Scan for devices"
            statusView.text = "Disconnected"

        } else {
            startBleScan()
        }
    }

    private fun showDeviceDialog() {

        deviceListView = ListView(this)

        deviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )

        deviceListView.adapter = deviceAdapter

        val emptyView = TextView(this).apply {
            text = "Searching for BLE devices...\nWaiting for scan callback"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            textSize = 14f
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                emptyView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(120)
                )
            )

            addView(
                deviceListView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(300)
                )
            )
        }

        deviceListView.emptyView = emptyView

        deviceDialog = AlertDialog.Builder(this)
            .setTitle("Available Bluetooth Devices")
            .setView(container)
            .setNegativeButton("Stop scanning") { _, _ ->
                bleManager.stopScan()
                statusView.text = "BLE scan stopped"
            }
            .create()

        deviceListView.setOnItemClickListener { _, _, position, _ ->

            if (position < scannedDevices.size) {

                val selectedDevice = scannedDevices[position]

                bleManager.stopScan()

                deviceDialog?.dismiss()

                statusView.text =
                    "Connecting to ${selectedDevice.name}..."

                connectButton.isEnabled = false

                bleManager.connect(selectedDevice.device)
            }
        }

        deviceDialog?.setOnCancelListener {
            bleManager.stopScan()
        }

        deviceDialog?.show()
    }

    // --------------------------------------------------
    // CREATE UI
    // --------------------------------------------------

    private fun createUI() {

        val background = Color.rgb(17, 19, 27)
        val foreground = Color.WHITE
        val secondary = Color.rgb(165, 169, 183)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(background)
        }

        val heading = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(secondary)
            gravity = Gravity.CENTER
        }

        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(39, 42, 54))
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Album artwork"
        }

        titleView = TextView(this).apply {
            text = "No track playing"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(foreground)
            gravity = Gravity.CENTER
            maxLines = 2
        }

        artistView = TextView(this).apply {
            text = "Play music on your device"
            textSize = 16f
            setTextColor(secondary)
            gravity = Gravity.CENTER
        }

        progressBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            isEnabled = false

            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(255, 165, 0)
                )

            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(75, 78, 90)
                )

            thumbTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.WHITE
                )
        }

        elapsedView = TextView(this).apply {
            text = "0:00"
            textSize = 12f
            setTextColor(secondary)
        }

        durationView = TextView(this).apply {
            text = "0:00"
            textSize = 12f
            setTextColor(secondary)
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }

        timeRow.addView(
            elapsedView,
            LinearLayout.LayoutParams(0, dp(24), 1f)
        )

        timeRow.addView(
            durationView,
            LinearLayout.LayoutParams(0, dp(24), 1f).apply {
                gravity = Gravity.END
            }
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val previousButton = makeControlButton("⏮", 25f)
        playPauseButton = makeControlButton("▶", 29f)
        val nextButton = makeControlButton("⏭", 25f)

        previousButton.setOnClickListener {
            MediaListenerService.activeController
                ?.transportControls?.skipToPrevious()
        }

        playPauseButton.setOnClickListener {
            val controller = MediaListenerService.activeController
                ?: return@setOnClickListener

            val state = controller.playbackState?.state

            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }

        nextButton.setOnClickListener {
            MediaListenerService.activeController
                ?.transportControls?.skipToNext()
        }

        controls.addView(previousButton)
        controls.addView(playPauseButton)
        controls.addView(nextButton)

        // BLE connection button

        connectButton = Button(this).apply {
            text = "Scan for devices"
            textSize = 14f
            isAllCaps = false

            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(39, 120, 105)
                )

            setTextColor(Color.WHITE)

            setOnClickListener {
                handleConnectButton()
            }
        }

        statusView = TextView(this).apply {
            text = "Waiting for media session..."
            textSize = 12f
            setTextColor(secondary)
            gravity = Gravity.CENTER
        }

        // Add views

        root.addView(
            heading,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(35)
            )
        )

        root.addView(
            artworkView,
            LinearLayout.LayoutParams(dp(260), dp(260)).apply {
                gravity = Gravity.CENTER
                topMargin = dp(18)
                bottomMargin = dp(28)
            }
        )

        root.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            artistView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )

        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(35)
            )
        )

        root.addView(
            timeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
            )
        )

        root.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(90)
            )
        )

        root.addView(
            connectButton,
            LinearLayout.LayoutParams(
                dp(220),
                dp(48)
            ).apply {
                topMargin = dp(8)
            }
        )

        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )

        setContentView(root)
    }

    // --------------------------------------------------
    // CONTROL BUTTON
    // --------------------------------------------------

    private fun makeControlButton(
        symbol: String,
        size: Float
    ): Button {

        return Button(this).apply {
            text = symbol
            textSize = size
            setTextColor(Color.WHITE)
            isAllCaps = false

            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(39, 42, 54)
                )

            layoutParams = LinearLayout.LayoutParams(
                dp(76),
                dp(64)
            ).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            }
        }
    }

    // --------------------------------------------------
    // SEND MEDIA METADATA TO ESP32
    // --------------------------------------------------

    private fun updateTrackIdentity(
        title: String,
        artist: String,
        duration: Long
    ): Boolean {

        val newTrackKey = listOf(
            title,
            artist,
            duration.toString()
        ).joinToString("|")

        if (newTrackKey == lastTrackKey) {
            return false
        }

        lastTrackKey = newTrackKey
        lastArtworkHash = null

        currentTrackId = nextTrackId

        nextTrackId++

        if (nextTrackId > 65535) {
            nextTrackId = 1
        }

        Log.d(
            "MainActivity",
            "Track changed: ID=$currentTrackId, $title - $artist"
        )

        return true
    }

    private fun sendCurrentMetadata() {

        if (!esp32Connected) return

        try {
            val metadata = JSONObject().apply {
                put("title", titleView.text.toString().take(40))
                put("artist", artistView.text.toString().take(40))
                put("duration", currentDuration)
                put("position", currentPosition)
                put("playing", isPlaying)
            }

            val payload = metadata.toString().toByteArray(Charsets.UTF_8)

            bleManager.sendMetadata(
                currentTrackId,
                payload
            )

            Log.d(
                "MainActivity",
                "Metadata queued: track=$currentTrackId, $metadata"
            )

        } catch (e: Exception) {
            Log.e(
                "MainActivity",
                "Metadata transfer preparation failed",
                e
            )
        }
    }

    // --------------------------------------------------
    // MEDIA UI UPDATE
    // --------------------------------------------------

    private fun updateUI(snapshot: MediaSnapshot?) {

        if (snapshot == null) {

            lastTrackKey = null
            lastArtworkHash = null

            titleView.text = "No track playing"
            artistView.text = "Play music on your device"

            artworkView.setImageResource(
                android.R.drawable.ic_media_play
            )

            currentDuration = 0L
            currentPosition = 0L
            isPlaying = false

            positionAnchor = 0L
            positionAnchorTime = SystemClock.elapsedRealtime()

            playPauseButton.text = "▶"
            statusView.text = "Waiting for media session..."

            updateProgress()
            sendCurrentMetadata()

            return
        }

        // Determine whether this is a new track.
        val trackChanged = updateTrackIdentity(
            snapshot.title,
            snapshot.artist,
            snapshot.duration
        )

        // Update metadata.
        titleView.text = snapshot.title
        artistView.text = snapshot.artist

        currentDuration = snapshot.duration.coerceAtLeast(0L)

        currentPosition = snapshot.position.coerceIn(
            0L,
            currentDuration
        )

        isPlaying = snapshot.playing

        // Metadata is queued after the current snapshot is updated.
        sendCurrentMetadata()

        // Establish a fresh playback position reference.
        positionAnchor = currentPosition
        positionAnchorTime = SystemClock.elapsedRealtime()

        // Clear old artwork when a new track starts.
        if (trackChanged) {
            artworkView.setImageResource(
                android.R.drawable.ic_media_play
            )
        }

        // Update artwork.
        snapshot.artwork?.let { bitmap ->

            artworkView.setImageBitmap(bitmap)

            if (esp32Connected) {
                try {
                    val rgb565Data = convertArtworkToRGB565(bitmap)
                    val artworkHash = rgb565Data.contentHashCode()

                    if (
                        trackChanged ||
                        artworkHash != lastArtworkHash
                    ) {

                        bleManager.sendArtwork(
                            currentTrackId,
                            rgb565Data
                        )

                        lastArtworkHash = artworkHash

                        Log.d(
                            "MainActivity",
                            "Artwork queued: track=$currentTrackId, " +
                                    "${rgb565Data.size} bytes"
                        )
                    }

                } catch (e: Exception) {
                    Log.e(
                        "MainActivity",
                        "Artwork conversion failed",
                        e
                    )
                }
            }

        } ?: run {
            if (trackChanged) {
                artworkView.setImageResource(
                    android.R.drawable.ic_media_play
                )
            }
        }

        playPauseButton.text = if (isPlaying) "⏸" else "▶"

        if (statusView.text.toString().startsWith("Waiting for media")) {
            statusView.text = if (isPlaying) {
                "Now playing"
            } else {
                "Playback paused"
            }
        }

        updateProgress()

        
    }

    
    // --------------------------------------------------
    // CONVERT BITMAP TO RGB565
    // --------------------------------------------------

        
    private fun convertArtworkToRGB565(bitmap: Bitmap): ByteArray {

        val size = 240

        // Calculate center-crop dimensions while preserving aspect ratio.
        val scale = maxOf(
            size.toFloat() / bitmap.width,
            size.toFloat() / bitmap.height
        )

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(
            bitmap,
            scaledWidth,
            scaledHeight,
            true
        )

        // Crop the centre into a 240x240 square.
        val xOffset = (scaledWidth - size) / 2
        val yOffset = (scaledHeight - size) / 2

        val croppedBitmap = Bitmap.createBitmap(
            scaledBitmap,
            xOffset,
            yOffset,
            size,
            size
        )

        val pixels = IntArray(size * size)

        croppedBitmap.getPixels(
            pixels,
            0,
            size,
            0,
            0,
            size,
            size
        )

        val output = ByteArray(size * size * 2)

        for (i in pixels.indices) {
            val pixel = pixels[i]

            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF

            val rgb565 =
                ((red shr 3) shl 11) or
                ((green shr 2) shl 5) or
                (blue shr 3)

            // Little-endian RGB565
            output[i * 2] = (rgb565 and 0xFF).toByte()
            output[i * 2 + 1] = ((rgb565 shr 8) and 0xFF).toByte()
        }

        if (croppedBitmap !== scaledBitmap) {
            croppedBitmap.recycle()
        }

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return output
    }

        
    // --------------------------------------------------
    // SEND ALBUM ARTWORK TO ESP32
    // --------------------------------------------------

    private fun sendArtworkToESP32(bitmap: Bitmap?) {

        if (!esp32Connected || bitmap == null) return

        try {
            val rgb565Data = convertArtworkToRGB565(bitmap)

            bleManager.sendArtwork(currentTrackId, rgb565Data)

            Log.d(
                "MainActivity",
                "Artwork queued: ${rgb565Data.size} bytes"
            )

        } catch (e: Exception) {
            Log.e(
                "MainActivity",
                "Artwork conversion failed",
                e
            )
        }
    }

    // --------------------------------------------------
    // PROGRESS
    // --------------------------------------------------

    private fun updateProgress() {

        val duration = currentDuration.coerceAtLeast(0L)
        val position = currentPosition.coerceIn(0L, duration)

        progressBar.progress = if (duration > 0) {
            ((position.toDouble() / duration) * 1000).toInt()
        } else {
            0
        }

        elapsedView.text = formatTime(position)
        durationView.text = formatTime(duration)
    }

    private fun formatTime(milliseconds: Long): String {

        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return "%d:%02d".format(minutes, seconds)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}