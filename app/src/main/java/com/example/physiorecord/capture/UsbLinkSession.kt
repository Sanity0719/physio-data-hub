package com.example.physiorecord.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * USB 串口会话（Android USB Host，不引第三方库）。
 *
 * 最短路径：找设备 → 等授权 → 打开 → 认领 0 号接口 → 找批量收发端点 →
 * 起一条读线程收包 → 与 TCP/SPP 共用“发一行读一行”。
 * 真机上请用 OTG 接串口芯片（CDC/FTDI/CH340 等），模拟器没有 USB 设备，会得到“没有找到 USB 设备”。
 */
class UsbLinkSession(
    private val context: Context,
    private val deviceId: String,
    val channelId: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 3000
) : LinkSession {

    @Volatile
    override var status = LinkStatus(deviceId, channelId)
        private set

    private val incoming = LinkedBlockingQueue<String>()
    private var connection: UsbDeviceConnection? = null
    private var outEndpoint: UsbEndpoint? = null
    private var reader: Thread? = null
    @Volatile
    private var running = false

    override fun connect(): LinkStatus {
        if (status.state == LinkState.CONNECTED) return status
        status = status.copy(
            state = LinkState.CONNECTING,
            since = System.currentTimeMillis(),
            attempt = status.attempt + 1,
            lastError = null
        )
        try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = manager.deviceList.values.firstOrNull()
                ?: throw IllegalStateException("没有找到 USB 设备（OTG 未接）")
            if (!manager.hasPermission(device)) {
                requestPermission(manager, device)
            }
            if (!manager.hasPermission(device)) {
                throw IllegalStateException("USB 权限被拒绝")
            }
            val conn = manager.openDevice(device)
                ?: throw IllegalStateException("打开 USB 设备失败")
            val iface = device.getInterface(0)
                ?: throw IllegalStateException("USB 设备没有可用接口")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw IllegalStateException("认领 USB 接口失败（可能被其它程序占用）")
            }
            var out: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i) ?: continue
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) out = ep
            }
            outEndpoint = out ?: run {
                conn.close()
                throw IllegalStateException("USB 设备没有批量写端点")
            }
            connection = conn
            running = true
            reader = Thread({ readLoop(conn, iface) }, "usb-read-$deviceId").apply {
                isDaemon = true
                start()
            }
            status = status.copy(state = LinkState.CONNECTED, since = System.currentTimeMillis(), lastError = null)
            android.util.Log.d("Link", "device=$deviceId channel=$channelId state=CONNECTED usb=${device.deviceName}")
        } catch (e: Exception) {
            fail(reason(e))
        }
        return status
    }

    private fun requestPermission(manager: UsbManager, device: UsbDevice) {
        val flag = if (android.os.Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flag
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                runCatching { context.unregisterReceiver(this) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        manager.requestPermission(device, intent)
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        while (System.currentTimeMillis() < deadline && !manager.hasPermission(device)) {
            Thread.sleep(100)
        }
    }

    private fun readLoop(conn: UsbDeviceConnection, iface: android.hardware.usb.UsbInterface) {
        val buffer = ByteArray(1024)
        val sb = StringBuilder()
        while (running) {
            val n = try {
                var read = -1
                for (i in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(i) ?: continue
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) {
                        read = conn.bulkTransfer(ep, buffer, buffer.size, 200)
                        break
                    }
                }
                read
            } catch (e: Exception) {
                -1
            }
            if (n > 0) {
                sb.append(String(buffer, 0, n, Charsets.UTF_8))
                var idx: Int
                while (true) {
                    idx = sb.indexOf("\n").takeIf { it >= 0 } ?: sb.indexOf("\r")
                    if (idx < 0) break
                    val line = sb.substring(0, idx).trim()
                    sb.delete(0, idx + 1)
                    if (line.isNotEmpty()) incoming.offer(line)
                }
            }
        }
    }

    override fun sendLine(line: String): String {
        if (status.state != LinkState.CONNECTED) throw IllegalStateException("未连接")
        val conn = connection ?: throw IllegalStateException("未连接")
        val ep = outEndpoint ?: throw IllegalStateException("未连接")
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val n = conn.bulkTransfer(ep, bytes, bytes.size, readTimeoutMs)
        if (n < 0) throw IllegalStateException("USB 写失败")
        return incoming.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            ?: throw ReadTimeoutException("读超时（USB）")
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
        running = false
        reader?.join(300)
        reader = null
        runCatching { connection?.close() }
        connection = null
        outEndpoint = null
        incoming.clear()
    }

    private fun reason(e: Exception): String = when (e) {
        is SecurityException -> "缺少 USB 权限"
        is IllegalStateException -> e.message ?: "USB 连接失败"
        else -> (e.message ?: e.javaClass.simpleName)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.physiorecord.USB_PERMISSION"
    }
}
