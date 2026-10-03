
package com.sudesh.nowplaying

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

class BLEManager(
    private val context: Context,
    private val listener: BLEListener
) {

    companion object {
        private const val TAG = "BLEManager"

        const val DEVICE_NAME = "NowPlaying-S3"

        const val SERVICE_UUID =
            "8d3f0001-7b3a-4f2a-9c1d-6e5a4b3c2d10"

        const val META_UUID =
            "8d3f0002-7b3a-4f2a-9c1d-6e5a4b3c2d10"

        const val ART_UUID =
            "8d3f0003-7b3a-4f2a-9c1d-6e5a4b3c2d10"

        const val CMD_UUID =
            "8d3f0004-7b3a-4f2a-9c1d-6e5a4b3c2d10"

        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /*
         * Packet format (all fields little-endian):
         *
         * Bytes 0-1: Track ID
         * Bytes 2-3: Transfer ID
         * Bytes 4-5: Sequence number
         * Bytes 6-7: Total chunks
         * Bytes 8-9: Nominal chunk size
         * Bytes 10+: Payload
         */
        private const val HEADER_SIZE = 10

        // Keep packets below the negotiated ATT payload limit.
        private const val MAX_CHUNK_DATA = 180

        private const val MAX_METADATA_CHUNKS = 40
        private const val MAX_ARTWORK_CHUNKS = 1400
    }

    interface BLEListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)

        fun onScanDevice(
            device: BluetoothDevice,
            name: String?,
            rssi: Int
        )

        fun onCommand(command: String) {}
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null

    var negotiatedMtu: Int = 23
        private set

    var metaCharacteristic: BluetoothGattCharacteristic? = null
        private set

    var artCharacteristic: BluetoothGattCharacteristic? = null
        private set

    var commandCharacteristic: BluetoothGattCharacteristic? = null
        private set

    private var scanning = false
    private var notificationsReady = false

    private data class PendingWrite(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val trackId: Int,
        val transferId: Int,
        val isArtwork: Boolean
    )

    private val writeQueue = ArrayDeque<PendingWrite>()

    /*
     * writeInProgress remains true while an acknowledged GATT write
     * is outstanding. Clearing queued packets does not cancel that
     * in-flight write.
     */
    private var writeInProgress = false
    private var inFlightCharacteristic: BluetoothGattCharacteristic? = null

    private var activeTrackId: Int? = null
    private var nextTransferId = 1

    // --------------------------------------------------
    // PERMISSIONS
    // --------------------------------------------------

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    // --------------------------------------------------
    // SCANNING
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBluetoothPermissions()) {
            listener.onError("Bluetooth permissions not granted")
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth is disabled")
            return
        }

        val bleScanner = scanner

        if (bleScanner == null) {
            listener.onError("BLE scanner unavailable")
            return
        }

        if (scanning) return

        try {
            bleScanner.startScan(scanCallback)
            scanning = true

            Log.d(TAG, "Unfiltered BLE scan started")

        } catch (e: SecurityException) {
            listener.onError("Bluetooth permission error: ${e.message}")
        } catch (e: Exception) {
            scanning = false
            listener.onError("BLE scan error: ${e.message}")
            Log.e(TAG, "Could not start BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasBluetoothPermissions()) return
        if (!scanning) return

        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            listener.onError("Could not stop scan: ${e.message}")
        }

        scanning = false
    }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device

            val name = try {
                result.scanRecord?.deviceName ?: device.name
            } catch (e: SecurityException) {
                null
            }

            Log.d(
                TAG,
                "Found: ${name ?: "Unknown"} | ${device.address} | ${result.rssi} dBm"
            )

            listener.onScanDevice(device, name, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onScanResult(
                    ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                    result
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            listener.onError("BLE scan failed: $errorCode")
        }
    }

    // --------------------------------------------------
    // CONNECTION
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            listener.onError("Bluetooth permissions not granted")
            return
        }

        stopScan()

        try {
            bluetoothGatt?.close()
            bluetoothGatt = null

            clearCharacteristics()
            resetWriteQueue()
            resetTransferState()

            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (bluetoothGatt == null) {
                listener.onError("Could not start BLE connection")
            }

        } catch (e: SecurityException) {
            listener.onError("Bluetooth connection error: ${e.message}")
        }
    }

    // --------------------------------------------------
    // GATT CALLBACKS
    // --------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("GATT connection failed: $status")

                gatt.close()

                if (gatt === bluetoothGatt) {
                    bluetoothGatt = null
                    clearCharacteristics()
                    resetWriteQueue()
                    resetTransferState()
                }

                listener.onDisconnected()
                return
            }

            when (newState) {

                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    negotiatedMtu = 23

                    Log.d(TAG, "Connected. Requesting MTU 247")

                    val requested = gatt.requestMtu(247)

                    if (!requested) {
                        Log.w(TAG, "MTU request could not start")
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (gatt === bluetoothGatt) {
                        clearCharacteristics()
                        resetWriteQueue()
                        resetTransferState()

                        gatt.close()
                        bluetoothGatt = null
                    } else {
                        gatt.close()
                    }

                    listener.onDisconnected()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d(TAG, "Negotiated MTU: $mtu")
            } else {
                negotiatedMtu = 23
                Log.w(TAG, "MTU negotiation failed: $status")
            }

            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed: $status")
                return
            }

            val service = gatt.getService(
                UUID.fromString(SERVICE_UUID)
            )

            if (service == null) {
                listener.onError("NowPlaying service not found")
                return
            }

            metaCharacteristic = service.getCharacteristic(
                UUID.fromString(META_UUID)
            )

            artCharacteristic = service.getCharacteristic(
                UUID.fromString(ART_UUID)
            )

            commandCharacteristic = service.getCharacteristic(
                UUID.fromString(CMD_UUID)
            )

            if (
                metaCharacteristic == null ||
                artCharacteristic == null ||
                commandCharacteristic == null
            ) {
                listener.onError(
                    "One or more BLE characteristics are missing"
                )
                return
            }

            enableCommandNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return
            if (descriptor.uuid != CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                notificationsReady = true

                Log.d(TAG, "Command notifications enabled")
                listener.onConnected()

            } else {
                notificationsReady = false

                listener.onError(
                    "Could not enable command notifications: $status"
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return

            synchronized(writeQueue) {
                if (!writeInProgress) return

                // Ignore an unexpected callback for another characteristic.
                if (characteristic !== inFlightCharacteristic) {
                    Log.w(TAG, "Unexpected characteristic write callback")
                    return
                }

                writeInProgress = false
                inFlightCharacteristic = null
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status")

                clearQueuedWrites()

                listener.onError(
                    "BLE data write failed: $status"
                )

                return
            }

            writeNextPacket()
        }

        @Deprecated("Use the value overload on newer Android versions")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicNotification(
                gatt,
                characteristic,
                characteristic.value ?: byteArrayOf()
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotification(
                gatt,
                characteristic,
                value
            )
        }
    }

    // --------------------------------------------------
    // ENABLE ESP32 COMMAND NOTIFICATIONS
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun enableCommandNotifications(gatt: BluetoothGatt) {
        val characteristic = commandCharacteristic ?: run {
            listener.onError("Command characteristic unavailable")
            return
        }

        val enabled = gatt.setCharacteristicNotification(
            characteristic,
            true
        )

        if (!enabled) {
            listener.onError("Could not enable characteristic notifications")
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)

        if (descriptor == null) {
            listener.onError("Command notification descriptor not found")
            return
        }

        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value =
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                    if (gatt.writeDescriptor(descriptor)) {
                        BluetoothStatusCodes.SUCCESS
                    } else {
                        BluetoothStatusCodes.ERROR_UNKNOWN
                    }
                }
            }

            if (result != BluetoothStatusCodes.SUCCESS) {
                listener.onError(
                    "Could not start notification descriptor write: $result"
                )
            }

        } catch (e: SecurityException) {
            listener.onError(
                "Notification permission error: ${e.message}"
            )
        }
    }

    // --------------------------------------------------
    // RECEIVE COMMANDS FROM ESP32
    // --------------------------------------------------

    private fun handleCharacteristicNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (gatt !== bluetoothGatt) return
        if (characteristic.uuid != UUID.fromString(CMD_UUID)) return

        val command = value.toString(Charsets.UTF_8).trim()

        if (command.isNotEmpty()) {
            Log.d(TAG, "ESP32 command received: $command")
            listener.onCommand(command)
        }
    }

    // --------------------------------------------------
    // PUBLIC METADATA / ARTWORK TRANSFER API
    // --------------------------------------------------

    /**
     * Start or update metadata for a track.
     *
     * trackId must remain the same for metadata and artwork
     * belonging to the same track.
     */
    fun sendMetadata(
        trackId: Int,
        data: ByteArray
    ) {
        if (!isValidTrackId(trackId)) {
            listener.onError("Invalid track ID: $trackId")
            return
        }

        val characteristic = metaCharacteristic ?: run {
            listener.onError("Metadata characteristic unavailable")
            return
        }

        if (!canTransfer()) return

        synchronized(writeQueue) {
            if (activeTrackId != trackId) {
                Log.d(
                    TAG,
                    "Track changed: $activeTrackId -> $trackId"
                )

                activeTrackId = trackId

                // An already-started GATT write cannot be cancelled.
                // Remove all remaining queued packets from the old track.
                writeQueue.clear()
            }
        }

        enqueueChunkedTransfer(
            characteristic = characteristic,
            data = data,
            trackId = trackId,
            isArtwork = false,
            maxChunks = MAX_METADATA_CHUNKS,
            prioritize = true
        )
    }

    /**
     * Queue artwork for the currently active track.
     *
     * Artwork for a track that is no longer active is rejected.
     */
    fun sendArtwork(
        trackId: Int,
        rgb565Data: ByteArray
    ) {
        if (!isValidTrackId(trackId)) {
            listener.onError("Invalid track ID: $trackId")
            return
        }

        val characteristic = artCharacteristic ?: run {
            listener.onError("Artwork characteristic unavailable")
            return
        }

        if (!canTransfer()) return

        synchronized(writeQueue) {
            if (activeTrackId != trackId) {
                Log.w(
                    TAG,
                    "Ignoring artwork for inactive track $trackId"
                )
                return
            }
        }

        enqueueChunkedTransfer(
            characteristic = characteristic,
            data = rgb565Data,
            trackId = trackId,
            isArtwork = true,
            maxChunks = MAX_ARTWORK_CHUNKS,
            prioritize = false
        )
    }

    private fun isValidTrackId(trackId: Int): Boolean {
        return trackId in 0..65535
    }

    private fun canTransfer(): Boolean {
        if (bluetoothGatt == null) {
            listener.onError("ESP32 is not connected")
            return false
        }

        if (!notificationsReady) {
            listener.onError("BLE connection is not ready")
            return false
        }

        return true
    }

    // --------------------------------------------------
    // TRANSFER ID
    // --------------------------------------------------

    private fun allocateTransferId(): Int {
        synchronized(writeQueue) {
            val result = nextTransferId

            nextTransferId++

            if (nextTransferId > 65535) {
                nextTransferId = 1
            }

            return result
        }
    }

    // --------------------------------------------------
    // CREATE CHUNKS
    // --------------------------------------------------

    private fun enqueueChunkedTransfer(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        trackId: Int,
        isArtwork: Boolean,
        maxChunks: Int,
        prioritize: Boolean
    ) {
        if (data.isEmpty()) {
            listener.onError("Cannot transfer empty data")
            return
        }

        /*
         * ATT payload = negotiated MTU - 3.
         * Custom protocol header = 10 bytes.
         */
        val chunkSize = minOf(
            MAX_CHUNK_DATA,
            negotiatedMtu - 3 - HEADER_SIZE
        )

        if (chunkSize <= 0) {
            listener.onError("Invalid negotiated MTU: $negotiatedMtu")
            return
        }

        val totalChunks =
            (data.size + chunkSize - 1) / chunkSize

        if (totalChunks > maxChunks || totalChunks > 65535) {
            listener.onError(
                "Transfer too large: $totalChunks chunks required"
            )
            return
        }

        val transferId = allocateTransferId()

        val packets = ArrayList<PendingWrite>(totalChunks)

        for (seq in 0 until totalChunks) {
            val offset = seq * chunkSize

            val payloadLength = minOf(
                chunkSize,
                data.size - offset
            )

            val packet = ByteArray(HEADER_SIZE + payloadLength)

            // Track ID.
            writeUInt16LE(packet, 0, trackId)

            // Transfer ID.
            writeUInt16LE(packet, 2, transferId)

            // Sequence number.
            writeUInt16LE(packet, 4, seq)

            // Total chunks.
            writeUInt16LE(packet, 6, totalChunks)

            // Nominal chunk size.
            writeUInt16LE(packet, 8, chunkSize)

            System.arraycopy(
                data,
                offset,
                packet,
                HEADER_SIZE,
                payloadLength
            )

            packets.add(
                PendingWrite(
                    characteristic = characteristic,
                    data = packet,
                    trackId = trackId,
                    transferId = transferId,
                    isArtwork = isArtwork
                )
            )
        }

        synchronized(writeQueue) {
            /*
             * Remove any queued transfer of the same type for this track.
             * This prevents old artwork or metadata from building up.
             */
            removeQueuedTransfers(
                trackId = trackId,
                isArtwork = isArtwork
            )

            if (prioritize) {
                // Insert metadata at the front, preserving packet order.
                for (index in packets.indices.reversed()) {
                    writeQueue.addFirst(packets[index])
                }
            } else {
                writeQueue.addAll(packets)
            }
        }

        Log.d(
            TAG,
            "Queued ${packets.size} packets: " +
                    "track=$trackId transfer=$transferId " +
                    "artwork=$isArtwork chunkSize=$chunkSize"
        )

        writeNextPacket()
    }

    private fun writeUInt16LE(
        destination: ByteArray,
        offset: Int,
        value: Int
    ) {
        destination[offset] = (value and 0xFF).toByte()
        destination[offset + 1] =
            ((value shr 8) and 0xFF).toByte()
    }

    private fun removeQueuedTransfers(
        trackId: Int,
        isArtwork: Boolean
    ) {
        val iterator = writeQueue.iterator()

        while (iterator.hasNext()) {
            val pending = iterator.next()

            if (
                pending.trackId == trackId &&
                pending.isArtwork == isArtwork
            ) {
                iterator.remove()
            }
        }
    }

    // --------------------------------------------------
    // SEQUENTIAL GATT WRITE QUEUE
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun writeNextPacket() {
        val gatt = bluetoothGatt ?: return

        val pending: PendingWrite

        synchronized(writeQueue) {
            if (writeInProgress || writeQueue.isEmpty()) return

            if (!notificationsReady) return

            pending = writeQueue.removeFirst()

            writeInProgress = true
            inFlightCharacteristic = pending.characteristic
        }

        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {

                gatt.writeCharacteristic(
                    pending.characteristic,
                    pending.data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )

            } else {

                @Suppress("DEPRECATION")
                run {
                    pending.characteristic.writeType =
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                    pending.characteristic.value = pending.data

                    if (gatt.writeCharacteristic(pending.characteristic)) {
                        BluetoothStatusCodes.SUCCESS
                    } else {
                        BluetoothStatusCodes.ERROR_UNKNOWN
                    }
                }
            }

            if (result != BluetoothStatusCodes.SUCCESS) {
                Log.e(TAG, "Could not start GATT write: $result")

                synchronized(writeQueue) {
                    writeInProgress = false
                    inFlightCharacteristic = null
                    writeQueue.clear()
                }

                listener.onError(
                    "Could not start BLE write: $result"
                )
            }

        } catch (e: SecurityException) {
            synchronized(writeQueue) {
                writeInProgress = false
                inFlightCharacteristic = null
                writeQueue.clear()
            }

            listener.onError(
                "BLE write permission error: ${e.message}"
            )
        }
    }

    // --------------------------------------------------
    // QUEUE MANAGEMENT
    // --------------------------------------------------

    /**
     * Clears queued packets but deliberately leaves an in-flight
     * GATT write alone. Its callback will release the queue.
     */
    private fun clearQueuedWrites() {
        synchronized(writeQueue) {
            writeQueue.clear()
        }
    }

    /**
     * Used when the GATT connection is being replaced or closed.
     */
    private fun resetWriteQueue() {
        synchronized(writeQueue) {
            writeQueue.clear()
            writeInProgress = false
            inFlightCharacteristic = null
        }
    }

    private fun resetTransferState() {
        synchronized(writeQueue) {
            activeTrackId = null
            nextTransferId = 1
        }
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    private fun clearCharacteristics() {
        metaCharacteristic = null
        artCharacteristic = null
        commandCharacteristic = null
        notificationsReady = false
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (!hasBluetoothPermissions()) return

        stopScan()

        resetWriteQueue()
        resetTransferState()

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()

        bluetoothGatt = null

        clearCharacteristics()
    }
}