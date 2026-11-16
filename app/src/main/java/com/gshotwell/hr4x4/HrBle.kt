package com.gshotwell.hr4x4

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
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE Heart Rate Service client (Polar H10 or any standard strap).
 *
 * Each attempt first scans for the strap's address and connects to the device object the
 * scan returns. Connecting straight from getRemoteDevice(address) silently hangs on many
 * phones when the strap uses a random address the stack hasn't just seen.
 */
@SuppressLint("MissingPermission")
class HrBle(private val ctx: Context) {
    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var name: String = "Strap"
    private var wanted = false
    private var attempts = 0
    private var everConnected = false
    private var scanning = false
    private var gotHr = false
    private var attemptId = 0

    private fun log(s: String) = Engine.log(s)

    fun connect(addr: String, displayName: String?) {
        if (addr == address && wanted && (gatt != null || scanning)) { log("Already connecting to $addr"); return }
        close()
        address = addr; name = displayName ?: "Strap"
        wanted = true; attempts = 0; everConnected = false
        log("Connect requested: $name ($addr)")
        attempt()
    }

    fun disconnect() {
        if (wanted) log("Disconnect requested")
        wanted = false
        close()
        Engine.setConn(Conn.NONE, "")
    }

    private fun close() {
        attemptId++
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private fun attempt() {
        if (!wanted) return
        val addr = address ?: return
        val a = adapter
        if (a == null) { log("No Bluetooth adapter"); return }
        if (!a.isEnabled) { log("Bluetooth is off – waiting"); retryLater(); return }
        attempts++
        if (!everConnected) Engine.setConn(Conn.CONNECTING, name)
        val scanner = a.bluetoothLeScanner
        if (scanner == null) { log("Scanner unavailable – trying direct connect"); connectTo(a.getRemoteDevice(addr)); return }
        log("Attempt $attempts: scanning for $addr")
        val id = attemptId
        scanning = true
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(addr).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCb
            )
        } catch (e: Exception) {
            scanning = false
            log("Scan failed to start: ${e.message} – trying direct connect")
            connectTo(a.getRemoteDevice(addr)); return
        }
        main.postDelayed({
            if (id != attemptId || !scanning) return@postDelayed
            stopScan()
            log("Strap not seen in ${SCAN_TIMEOUT_MS / 1000}s. Is it on your chest with damp electrodes? Trying direct connect.")
            connectTo(a.getRemoteDevice(addr))
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            main.post {
                if (!scanning) return@post
                stopScan()
                log("Found strap (RSSI ${r.rssi})")
                connectTo(r.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                log("Scan error $errorCode – trying direct connect")
                address?.let { a -> adapter?.getRemoteDevice(a)?.let { connectTo(it) } }
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun connectTo(dev: BluetoothDevice) {
        if (!wanted) return
        gatt?.let { try { it.close() } catch (_: Exception) {} }
        gotHr = false
        log("Opening GATT connection…")
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        val id = attemptId
        main.postDelayed({
            if (id == attemptId && wanted && !gotHr && Engine.conn != Conn.CONNECTED) {
                log("No connection after ${CONNECT_TIMEOUT_MS / 1000}s – retrying")
                failAndRetry()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun failAndRetry() {
        attemptId++
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        if (everConnected) Engine.setConn(Conn.LOST, name)
        retryLater()
    }

    private fun retryLater() {
        if (!wanted) return
        val delay = (1000L * attempts).coerceIn(1000L, 5000L)
        main.postDelayed({ attempt() }, delay)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                log("GATT state=${stateName(newState)} status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // Some stacks drop discovery issued immediately after connect.
                    main.postDelayed({ if (g === gatt) { val ok = g.discoverServices(); log("Discovering services ($ok)") } }, 600)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    failAndRetry()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { log("Service discovery failed ($status)"); failAndRetry(); return@post }
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (ch == null) {
                    log("Device has no heart-rate service. Services: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
                    failAndRetry(); return@post
                }
                val n = g.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(CCCD)
                if (d == null) { log("No CCCD descriptor; notifications set=$n – waiting for data"); return@post }
                val w: Any = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
                log("Enabling HR notifications (set=$n, write=$w)")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                log("Notifications enabled (status=$status)")
                attempts = 0; everConnected = true
                Engine.setConn(Conn.CONNECTED, name)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            parse(value)
        }

        @Deprecated("pre-API 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            ch.value?.let { parse(it) }
        }
    }

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "$s"
    }

    private fun parse(v: ByteArray) {
        if (v.size < 2) return
        val flags = v[0].toInt() and 0xff
        val hr = if (flags and 0x01 != 0 && v.size >= 3)
            (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8)
        else v[1].toInt() and 0xff
        if (hr <= 0) return
        main.post {
            if (!gotHr) {
                gotHr = true
                log("First heart rate: $hr bpm")
                if (Engine.conn != Conn.CONNECTED) { attempts = 0; everConnected = true; Engine.setConn(Conn.CONNECTED, name) }
            }
            Engine.onHr(hr)
        }
    }
}
