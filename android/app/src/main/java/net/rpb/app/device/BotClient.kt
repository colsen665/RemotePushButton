package net.rpb.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.rpb.core.device.BotCommands
import net.rpb.core.device.Presser
import java.util.UUID

/**
 * SwitchBot Bot over BLE GATT, using SwitchBot's published protocol (ADR 0003).
 * Connects by MAC address for each operation; the Bot must be in Press mode (set once in the SwitchBot app).
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

        val session = GattSession()
        val gatt = adapter.getRemoteDevice(address).connectGatt(context, false, session, BluetoothDevice.TRANSPORT_LE)
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
    }
}
