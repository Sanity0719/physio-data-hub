package com.example.physiorecord.capture

import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.link.OutboundClient
import com.example.physiorecord.ui.Prefs

/**
 * 网络（TCP）设备的数据源。
 *
 * 地址默认取设备登记里的通道；net_gateway 仍允许用界面里保存的地址/端口覆盖，
 * 这样同一套代码可以登记多台 TCP 设备，而不用给每台写一个类。
 */
class TcpSensorSource(
    private val prefs: Prefs? = null,
    override val key: String = "net_gateway",
    override val name: String = "局域网网关"
) : CaptureSource {

    override fun readOnce(): Int {
        val (host, port) = if (key == "net_gateway" && prefs != null) {
            prefs.sendHost to prefs.sendPort
        } else {
            val addr = DeviceRegistry.byKey(key)?.channels?.firstOrNull()?.address
                as? com.example.physiorecord.data.ChannelAddress.Network
            (addr?.host ?: "127.0.0.1") to (addr?.port ?: 8899)
        }
        val reply = OutboundClient.send(host, port, "READ")
        return parseReply(reply)?.values?.firstOrNull()
            ?: throw IllegalStateException("回包没有 value：$reply")
    }

    override fun parseReply(line: String): Map<String, Int>? {
        val m = Regex("""value\s*=\s*(-?\d+)""").find(line) ?: return null
        val metric = DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
        return mapOf(metric to m.groupValues[1].toInt())
    }
}
