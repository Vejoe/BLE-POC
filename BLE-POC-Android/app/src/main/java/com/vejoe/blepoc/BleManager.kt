package com.vejoe.blepoc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class LogEntry(
    val text: String,
    val incoming: Boolean,
    val time: Long = System.currentTimeMillis()
)

data class DiscoveredDevice(
    val device: BluetoothDevice,
    val name: String?,
    val rssi: Int,
    val pairCode: String?
)

object BleDefaults {
    const val SERVICE_UUID = "8f6d7f58-9ed7-4a95-8e09-5b4b8b8d1f10"
    const val WRITE_UUID = "4a38a38b-b6d9-43cc-8c02-44d4605dd002"
    const val NOTIFY_UUID = "4a38a38b-b6d9-43cc-8c02-44d4605dd002"
    const val PAIR_CODE = "123456"
}

private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Handles both roles:
 *  - Peripheral: BLE advertising + GATT server (can be written to and can notify)
 *  - Central: scanning + GATT client (can write to and subscribe to notifications)
 */
class BleManager(private val context: Context) {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private val _hasSubscribedCentral = MutableStateFlow(false)
    val hasSubscribedCentral: StateFlow<Boolean> = _hasSubscribedCentral.asStateFlow()

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    // ---- Peripheral ----
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverWriteChar: BluetoothGattCharacteristic? = null
    private var serverNotifyChar: BluetoothGattCharacteristic? = null
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    // ---- Central ----
    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var remoteServiceUuid: UUID? = null
    private var remoteWriteUuid: UUID? = null
    private var remoteNotifyUuid: UUID? = null
    private var remoteWriteChar: BluetoothGattCharacteristic? = null
    private var remoteNotifyChar: BluetoothGattCharacteristic? = null

    // ---------------------------------------------------------------- helpers

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun addLog(text: String, incoming: Boolean = false) {
        _logs.value = (_logs.value + LogEntry(text, incoming)).takeLast(300)
    }

    private fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value.trim()) }.getOrNull()

    // ---------------------------------------------------------- peripheral

    @SuppressLint("MissingPermission")
    fun startAdvertising(
        serviceUuid: String,
        writeUuid: String,
        notifyUuid: String,
        pairCode: String
    ) {
        if (!ensureBlePermissions()) {
            addLog("Missing Bluetooth permissions")
            return
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            addLog("Bluetooth is off or unavailable")
            return
        }

        val serviceUUID = parseUuid(serviceUuid) ?: run {
            addLog("Invalid service UUID")
            return
        }
        val writeUUID = parseUuid(writeUuid) ?: run {
            addLog("Invalid write characteristic UUID")
            return
        }
        val notifyUUID = parseUuid(notifyUuid) ?: run {
            addLog("Invalid notify characteristic UUID")
            return
        }

        if (_isAdvertising.value) stopAdvertising()

        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        if (writeUUID == notifyUUID) {
            // Same UUID for write & notify -> one read/write/notify characteristic
            val ch = BluetoothGattCharacteristic(
                writeUUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or
                    BluetoothGattCharacteristic.PERMISSION_READ
            )
            addCccd(ch)
            service.addCharacteristic(ch)
            serverWriteChar = ch
            serverNotifyChar = ch
        } else {
            val writeCh = BluetoothGattCharacteristic(
                writeUUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val notifyCh = BluetoothGattCharacteristic(
                notifyUUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            addCccd(notifyCh)
            service.addCharacteristic(writeCh)
            service.addCharacteristic(notifyCh)
            serverWriteChar = writeCh
            serverNotifyChar = notifyCh
        }

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer?.addService(service) != true) {
            addLog("Failed to add GATT service")
            gattServer?.close()
            gattServer = null
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Legacy advertising is limited to 31 bytes. A 128-bit service UUID already
        // takes ~18 bytes, so keep the advertisement minimal and put the device name
        // in the scan response instead.
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser = btAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            addLog("BLE advertising is not supported")
            return
        }
        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        _isAdvertising.value = true
        addLog("Started advertising $serviceUUID (pair code: $pairCode)")
    }

    private fun addCccd(ch: BluetoothGattCharacteristic) {
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        ch.addDescriptor(cccd)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val wasAdvertising = _isAdvertising.value
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        serverWriteChar = null
        serverNotifyChar = null
        subscribedDevices.clear()
        _hasSubscribedCentral.value = false
        _isAdvertising.value = false
        if (wasAdvertising) addLog("Stopped advertising")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            // Started successfully; logged by startAdvertising()
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            addLog("Advertising failed (code $errorCode)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addLog("Central connected: ${device.address}")
            } else {
                subscribedDevices.remove(device)
                _hasSubscribedCentral.value = subscribedDevices.isNotEmpty()
                addLog("Central disconnected: ${device.address}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val text = value?.toString(Charsets.UTF_8) ?: return
            addLog("Peer: $text", incoming = true)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: ByteArray(0)
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null && (
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    )
                if (enabled) subscribedDevices.add(device) else subscribedDevices.remove(device)
                _hasSubscribedCentral.value = subscribedDevices.isNotEmpty()
                addLog(
                    if (enabled) "Central subscribed: ${device.address}"
                    else "Central unsubscribed: ${device.address}"
                )
            }
        }
    }

    // ------------------------------------------------------------- central

    @SuppressLint("MissingPermission")
    fun startScanning(serviceUuid: String) {
        if (!ensureBlePermissions()) {
            addLog("Missing Bluetooth permissions")
            return
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            addLog("Bluetooth is off or unavailable")
            return
        }
        val serviceUUID = parseUuid(serviceUuid) ?: run {
            addLog("Invalid service UUID")
            return
        }

        if (_isScanning.value) stopScanning()
        _devices.value = emptyList()

        scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            addLog("BLE scanning is not supported")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        _isScanning.value = true
        addLog("Started scanning…")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
        val wasScanning = _isScanning.value
        _isScanning.value = false
        if (wasScanning) addLog("Stopped scanning")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = runCatching { device.name }.getOrNull()
            val pairCode = result.scanRecord?.serviceData?.values
                ?.firstOrNull()
                ?.toString(Charsets.UTF_8)
            val item = DiscoveredDevice(device, name, result.rssi, pairCode)
            val updated = _devices.value.filterNot { it.device.address == device.address } + item
            _devices.value = updated.sortedByDescending { it.rssi }
            addLog("Found device: ${name ?: "Unknown"}  RSSI:${result.rssi}")
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            addLog("Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice,
        serviceUuid: String,
        writeUuid: String,
        notifyUuid: String
    ) {
        if (!ensureBlePermissions()) {
            addLog("Missing Bluetooth permissions")
            return
        }
        remoteServiceUuid = parseUuid(serviceUuid) ?: run {
            addLog("Invalid service UUID")
            return
        }
        remoteWriteUuid = parseUuid(writeUuid) ?: run {
            addLog("Invalid write characteristic UUID")
            return
        }
        remoteNotifyUuid = parseUuid(notifyUuid) ?: run {
            addLog("Invalid notify characteristic UUID")
            return
        }

        stopScanning()
        remoteWriteChar = null
        remoteNotifyChar = null
        addLog("Connecting to ${runCatching { device.name }.getOrNull() ?: device.address}…")
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        remoteWriteChar = null
        remoteNotifyChar = null
        _connectedName.value = null
        addLog("Disconnected")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val name = runCatching { gatt.device.name }.getOrNull()
                _connectedName.value = name ?: gatt.device.address
                addLog("Connected to ${name ?: gatt.device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectedName.value = null
                addLog("Disconnected: ${gatt.device.address}")
                runCatching { gatt.close() }
                if (bluetoothGatt === gatt) bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("Service discovery failed ($status)")
                return
            }
            val service = remoteServiceUuid?.let { gatt.getService(it) }
            if (service == null) {
                addLog("Service not found")
                return
            }

            remoteWriteChar = remoteWriteUuid?.let { service.getCharacteristic(it) }
            remoteNotifyChar = remoteNotifyUuid?.let { service.getCharacteristic(it) }
            if (remoteNotifyChar == null && remoteWriteUuid == remoteNotifyUuid) {
                remoteNotifyChar = remoteWriteChar
            }

            remoteNotifyChar?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(
                            cccd,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
                addLog("Subscribed to notifications")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (value != null) {
                addLog("Peer: ${value.toString(Charsets.UTF_8)}", incoming = true)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            addLog("Peer: ${value.toString(Charsets.UTF_8)}", incoming = true)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            addLog(
                if (status == BluetoothGatt.GATT_SUCCESS) "Write succeeded"
                else "Write failed ($status)"
            )
        }
    }

    // ---------------------------------------------------------------- send

    @SuppressLint("MissingPermission")
    fun sendMessage(text: String) {
        if (text.isEmpty()) return

        // Central role: write to the connected peripheral
        val gatt = bluetoothGatt
        val writeChar = remoteWriteChar
        if (gatt != null && writeChar != null) {
            val data = text.toByteArray(Charsets.UTF_8)
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    writeChar,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                writeChar.value = data
                @Suppress("DEPRECATION")
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(writeChar)
            }
            addLog("Me (central): $text")
            if (!started) addLog("Write request failed to start")
            return
        }

        // Peripheral role: notify subscribed centrals
        val server = gattServer
        val notifyChar = serverNotifyChar
        if (server != null && notifyChar != null && subscribedDevices.isNotEmpty()) {
            val data = text.toByteArray(Charsets.UTF_8)
            for (device in subscribedDevices) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, notifyChar, false, data)
                } else {
                    @Suppress("DEPRECATION")
                    notifyChar.value = data
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, notifyChar, false)
                }
            }
            addLog("Me (peripheral): $text")
            return
        }

        addLog("No active connection to send")
    }
}
