package com.example.physiorecord.capture

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 低功耗蓝牙（GATT）会话。
 *
 * 与 TCP/SPP 共用 [LinkSession]：连接状态、断开、发一行读一行。
 * 这里做的是“直连已知 MAC”的最短路径：connectGatt → 发现服务 → 找一个可写且可通知的特征 →
 * 打开通知 → 收包进队列。扫描与配对不在本组件范围内。
 */
class BleLinkSession(
    private val context: Context,
    private val deviceId: String,
    val channelId: String,
    private val mac: String,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 3000
) : LinkSession {

    @Volatile
    override var status = LinkStatus(deviceId, channelId)
        private set

    private var gatt: BluetoothGatt? = null
    private val incoming = LinkedBlockingQueue<String>()
    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null

    override fun connect(): LinkStatus {
        if (status.state == LinkState.CONNECTED) return status
        status = status.copy(
            state = LinkState.CONNECTING,
            since = System.currentTimeMillis(),
            attempt = status.attempt + 1,
            lastError = null
        )
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: throw IllegalStateException("这台设备没有蓝牙适配器")
            if (!adapter.isEnabled) throw IllegalStateException("蓝牙未开启")
            val remote = adapter.getRemoteDevice(mac)
            gatt = remote.connectGatt(context, false, callback, BluetoothProfile.GATT)
            if (gatt == null) throw IllegalStateException("建立 GATT 连接失败（$mac）")
            // connectGatt 本身不阻塞也不报错：这里限时等状态变成 CONNECTED
            val deadline = System.currentTimeMillis() + connectTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (status.state == LinkState.CONNECTED) return status
                if (status.state == LinkState.FAILED) return status
                Thread.sleep(100)
            }
            if (status.state != LinkState.CONNECTED) {
                fail("连接超时（$mac）")
            }
        } catch (e: Exception) {
            fail(reason(e))
        }
        return status
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (status.state == LinkState.CONNECTING || status.state == LinkState.CONNECTED) {
                    fail("连接已断开（$mac）")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败（GATT $statusCode）")
                return
            }
            val chars = g.services.flatMap { it.characteristics }
            val notify = chars.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val writable = chars.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (notify == null || writable == null) {
                fail("设备没有可用的读写特征（$mac）")
                return
            }
            writeChar = writable
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                g.writeDescriptor(cccd)
            }
            status = status.copy(state = LinkState.CONNECTED, since = System.currentTimeMillis(), lastError = null)
            android.util.Log.d("Link", "device=$deviceId channel=$channelId state=CONNECTED mac=$mac")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val text = c.value?.toString(Charsets.UTF_8) ?: return
            for (line in text.split('\n', '\r')) {
                if (line.isNotBlank()) incoming.offer(line.trim())
            }
        }
    }

    override fun sendLine(line: String): String {
        if (status.state != LinkState.CONNECTED) throw IllegalStateException("未连接")
        val g = gatt ?: throw IllegalStateException("未连接")
        val c = writeChar ?: throw IllegalStateException("未连接")
        c.value = (line + "\n").toByteArray(Charsets.UTF_8)
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        g.writeCharacteristic(c)
        return incoming.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            ?: throw ReadTimeoutException("读超时（$mac）")
    }

    @Synchronized
    override fun disconnect() {
        closeQuietly()
        status = status.copy(state = LinkState.IDLE, since = System.currentTimeMillis(), lastError = null)
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=IDLE")
    }

    @Synchronized
    private fun fail(message: String) {
        if (status.state == LinkState.IDLE) return
        closeQuietly()
        status = status.copy(state = LinkState.FAILED, since = System.currentTimeMillis(), lastError = message)
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=FAILED err=$message")
    }

    private fun closeQuietly() {
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        incoming.clear()
    }

    private fun reason(e: Exception): String = when (e) {
        is IllegalArgumentException -> "蓝牙地址格式不对（$mac）"
        is SecurityException -> "缺少蓝牙权限"
        is IllegalStateException -> e.message ?: "连接失败（$mac）"
        else -> (e.message ?: e.javaClass.simpleName) + "（$mac）"
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
