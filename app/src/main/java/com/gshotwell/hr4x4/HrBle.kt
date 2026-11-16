package com.gshotwell.hr4x4

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Minimal BLE Heart Rate Service client (works with Polar H10 and any standard strap). */
@SuppressLint("MissingPermission")
class HrBle(private val ctx: Context) {
    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var name: String = "Strap"
    private var wanted = false
    private var retries = 0
    private var everConnected = false

    fun connect(addr: String, displayName: String?) {
        if (addr == address && wanted && gatt != null) return
        close()
        address = addr; name = displayName ?: "Strap"
        wanted = true; retries = 0; everConnected = false
        open()
    }

    fun disconnect() {
        wanted = false
        close()
        Engine.setConn(Conn.NONE, "")
    }

    private fun close() {
        main.removeCallbacksAndMessages(null)
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private fun open() {
        val addr = address ?: return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { scheduleRetry(); return }
        if (!everConnected) Engine.setConn(Conn.CONNECTING, name)
        val dev = adapter.getRemoteDevice(addr)
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleRetry() {
        if (!wanted) return
        retries++
        val delay = (1000L * retries).coerceAtMost(5000L)
        main.postDelayed({ if (wanted && gatt == null) open() }, delay)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                    if (everConnected) Engine.setConn(Conn.LOST, name)
                    scheduleRetry()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) {
                main.post { Engine.setConn(Conn.LOST, "$name: no HR service"); g.disconnect() }
                return
            }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                retries = 0; everConnected = true
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

    private fun parse(v: ByteArray) {
        if (v.size < 2) return
        val flags = v[0].toInt() and 0xff
        val hr = if (flags and 0x01 != 0 && v.size >= 3)
            (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8)
        else v[1].toInt() and 0xff
        if (hr > 0) main.post { Engine.onHr(hr) }
    }
}
