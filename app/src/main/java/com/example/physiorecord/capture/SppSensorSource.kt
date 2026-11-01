package com.example.physiorecord.capture

import com.example.physiorecord.data.DeviceRegistry

/**
 * 经典蓝牙串口设备的数据源：读走长连接会话，这里只保留回包解析。
 * key/name 可传，便于登记多台同类设备。
 */
class SppSensorSource(
    override val key: String = "spp_sensor",
    override val name: String = "串口模块"
) : CaptureSource {

    override fun readOnce(): Int =
        throw IllegalStateException("SPP 是长连接，请先连接再读")

    override fun parseReply(line: String): Map<String, Int>? {
        val m = Regex("""value\s*=\s*(-?\d+)""").find(line) ?: return null
        val metric = DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
        return mapOf(metric to m.groupValues[1].toInt())
    }
}
