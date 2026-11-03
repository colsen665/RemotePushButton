package net.rpb.app.device

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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.rpb.core.device.BotCommands
import net.rpb.core.device.Presser
import java.util.UUID

/**
 * SwitchBot Bot over BLE GATT, using SwitchBot's published protocol (ADR 0003).
 * Connects by MAC address for each operation; the Bot must be in Press mode (set once in the SwitchBot app).
 * The first connect, and the one after a failed attempt, scans for that address first (ADR 0031).
 * [password] is the Bot's optional password from the SwitchBot app, blank for none (ADR 0025).
 */
class BotClient(
    private val context: Context,
    private val mac: () -> String,
    private val password: () -> String,
    private val log: (String) -> Unit,
) : Presser {
    // The Bot accepts one GATT connection at a time; serialise presses and battery reads.
    private val mutex = Mutex()

    // The device to connect to, from a scan where one found the Bot; null makes the next connect scan. Guarded by [mutex].
    private var device: BluetoothDevice? = null

    override suspend fun press() {
        val response = withRetry { command(BotCommands.press(password())) }
        if (response.isEmpty() || response[0] != STATUS_OK) {
            throw IllegalStateException("Bot replied with status ${response.firstOrNull()?.toInt()?.and(0xFF)}")
        }
    }

    /** Battery percentage from the Bot's basic-info response, or null if unavailable. */
    suspend fun battery(): Int? = runCatching {
        val response = withRetry { command(BotCommands.basicInfo(password())) }
        check(response.size >= 2 && response[0] == STATUS_OK) { "Bot replied with status ${response.firstOrNull()?.toInt()?.and(0xFF)}" }
        response[1].toInt() and 0xFF
    }.onFailure { log("Bot battery read failed: $it") }.getOrNull()

    private suspend fun <T> withRetry(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } catch (e: Exception) {
            log("Bot attempt failed ($e), retrying")
            device = null
            delay(500)
            block()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun command(payload: ByteArray): ByteArray {
        check(hasPermission()) { "Bluetooth permission not granted" }
        val address = mac().trim().uppercase()
        check(BluetoothAdapterCompat.isValid(address)) { "Bot MAC address not set" }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("no Bluetooth adapter")
        check(adapter.isEnabled) { "Bluetooth is off" }

        val target = device?.takeIf { it.address == address } ?: find(adapter, address).also { device = it }
        val session = GattSession()
        val gatt = target.connectGatt(context, false, session, BluetoothDevice.TRANSPORT_LE)
        try {
            return withTimeout(OPERATION_TIMEOUT_MS) {
                session.connected.await()
                gatt.discoverServices()
                session.servicesDiscovered.await()
                val service = gatt.getService(SERVICE) ?: error("Bot service not found — wrong MAC?")
                val tx = service.getCharacteristic(TX) ?: error("Bot write characteristic missing")
                val rx = service.getCharacteristic(RX) ?: error("Bot notify characteristic missing")

                gatt.setCharacteristicNotification(rx, true)
                val cccd = rx.getDescriptor(CCCD) ?: error("Bot notify descriptor missing")
                writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                session.descriptorWritten.await()

                writeCharacteristic(gatt, tx, payload)
                session.notifications.receive()
            }
        } finally {
            gatt.disconnect()
            gatt.close()
        }
    }

    /**
     * The Bot as a short scan for its address finds it, or else as [BluetoothAdapter.getRemoteDevice] gives it.
     * Until the phone has seen the Bot advertise since boot, a direct connect by address never completes (ADR 0031).
     */
    private suspend fun find(adapter: BluetoothAdapter, address: String): BluetoothDevice {
        val direct = { adapter.getRemoteDevice(address) }
        if (!hasScanPermission()) {
            log("Bluetooth scan permission not granted; connecting to the Bot without a scan")
            return direct()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
        ) {
            log("Location is off, so Android 11 and below can't scan; connecting to the Bot without a scan")
            return direct()
        }
        val found = scan(adapter, address)
        if (found == null) log("Bot not seen in a ${SCAN_TIMEOUT_MS / 1000} s scan; connecting without one")
        return found ?: direct()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scan(adapter: BluetoothAdapter, address: String): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val result = CompletableDeferred<BluetoothDevice?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                result.complete(scanResult.device)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Bluetooth scan failed ($errorCode)")
                result.complete(null)
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        return try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { result.await() }
        } finally {
            // Throws if Bluetooth went off meanwhile.
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, type)
        } else {
            characteristic.writeType = type
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        scanPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Turns GATT callbacks into awaitable signals. */
    private class GattSession : BluetoothGattCallback() {
        val connected = CompletableDeferred<Unit>()
        val servicesDiscovered = CompletableDeferred<Unit>()
        val descriptorWritten = CompletableDeferred<Unit>()
        val notifications = Channel<ByteArray>(Channel.UNLIMITED)

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else {
                val error = IllegalStateException("Bot connection failed (status $status, state $newState)")
                connected.completeExceptionally(error)
                servicesDiscovered.completeExceptionally(error)
                descriptorWritten.completeExceptionally(error)
                notifications.close(error)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
            else servicesDiscovered.completeExceptionally(IllegalStateException("service discovery failed ($status)"))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) descriptorWritten.complete(Unit)
            else descriptorWritten.completeExceptionally(IllegalStateException("enabling notifications failed ($status)"))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(value)
        }

        @Deprecated("Pre-Android 13 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Android 13+ delivers the value through the overload above.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notifications.trySend(characteristic.value ?: ByteArray(0))
        }
    }

    private object BluetoothAdapterCompat {
        private val pattern = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        fun isValid(mac: String) = pattern.matches(mac)
    }

    companion object {
        private val SERVICE = UUID.fromString("cba20d00-224d-11e6-9fb8-0002a5d5c51b")
        private val TX = UUID.fromString("cba20002-224d-11e6-9fb8-0002a5d5c51b")
        private val RX = UUID.fromString("cba20003-224d-11e6-9fb8-0002a5d5c51b")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val STATUS_OK: Byte = 0x01
        private const val OPERATION_TIMEOUT_MS = 10_000L
        private const val SCAN_TIMEOUT_MS = 5_000L

        /** What the scan before a connect needs; without it the Bot is connected without a scan (ADR 0031). */
        fun scanPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
