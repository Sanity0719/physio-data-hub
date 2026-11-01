package com.example.physiorecord.capture

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 经典蓝牙（SPP / RFCOMM）会话。
 *
 * 和 TCP 会话同一套上层接口：连接状态、收包线程、发一行读一行。
 * BluetoothSocket.connect() 本身没有超时，这里用“限时 join + 超时就 close”兜住。
 */
class SppLinkSession(
    private val deviceId: String,
    val channelId: String,
    private val mac: String,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 3000
) : LinkSession {

    @Volatile
    override var status = LinkStatus(deviceId, channelId)
        private set

    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val incoming = LinkedBlockingQueue<String>()
    @Volatile
    private var readerThread: Thread? = null

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
            val s = remote.createRfcommSocketToServiceRecord(SPP_UUID)
            val connector = Thread({
                try {
                    s.connect()
                } catch (_: Exception) {
                }
            }, "spp-connect-$deviceId")
            connector.start()
            connector.join(connectTimeoutMs.toLong())
            if (connector.isAlive) {
                runCatching { s.close() }
                throw java.net.SocketTimeoutException("连接超时")
            }
            if (!s.isConnected) {
                runCatching { s.close() }
                throw java.io.IOException("设备拒绝连接")
            }
            socket = s
            reader = BufferedReader(InputStreamReader(s.inputStream))
            writer = BufferedWriter(OutputStreamWriter(s.outputStream))
            incoming.clear()
            readerThread = Thread({ readLoop() }, "spp-reader-$deviceId").also { it.start() }
            status = status.copy(state = LinkState.CONNECTED, since = System.currentTimeMillis(), lastError = null)
            android.util.Log.d("Link", "device=$deviceId channel=$channelId state=CONNECTED mac=$mac")
        } catch (e: Exception) {
            closeQuietly()
            status = status.copy(
                state = LinkState.FAILED,
                since = System.currentTimeMillis(),
                lastError = reason(e)
            )
            android.util.Log.d(
                "Link",
                "device=$deviceId channel=$channelId state=FAILED attempt=${status.attempt} err=${status.lastError}"
            )
        }
        return status
    }

    private fun readLoop() {
        try {
            val r = reader ?: return
            while (true) {
                val line = r.readLine() ?: break
                incoming.offer(line)
            }
            peerClosed("对端已断开（$mac）")
        } catch (e: Exception) {
            if (status.state == LinkState.CONNECTED || status.state == LinkState.CONNECTING) {
                peerClosed(reason(e))
            }
        }
    }

    @Synchronized
    private fun peerClosed(message: String) {
        if (status.state == LinkState.IDLE) return
        closeQuietly()
        status = status.copy(state = LinkState.FAILED, since = System.currentTimeMillis(), lastError = message)
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=FAILED err=$message")
    }

    override fun sendLine(line: String): String {
        if (status.state != LinkState.CONNECTED) throw IllegalStateException("未连接")
        val w = writer ?: throw IllegalStateException("未连接")
        w.write(line)
        w.newLine()
        w.flush()
        return incoming.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            ?: throw ReadTimeoutException("读超时（$mac）")
    }

    @Synchronized
    override fun disconnect() {
        closeQuietly()
        status = status.copy(state = LinkState.IDLE, since = System.currentTimeMillis(), lastError = null)
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=IDLE")
    }

    private fun reason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时（$mac）"
        is IllegalArgumentException -> "蓝牙地址格式不对（$mac）"
        is SecurityException -> "缺少蓝牙权限"
        is IllegalStateException -> e.message ?: "连接失败（$mac）"
        is java.io.IOException -> (e.message ?: "连接失败") + "（$mac）"
        else -> (e.message ?: e.javaClass.simpleName) + "（$mac）"
    }

    private fun closeQuietly() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        readerThread?.interrupt()
        readerThread = null
        reader = null
        writer = null
        socket = null
        incoming.clear()
    }

    companion object {
        /** 标准串口服务 UUID（SPP） */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
