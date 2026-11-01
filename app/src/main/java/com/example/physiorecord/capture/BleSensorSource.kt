package com.example.physiorecord.capture

import com.example.physiorecord.data.DeviceRegistry

/** 低功耗蓝牙设备的数据源：读走长连接会话，这里只保留回包解析。 */
class BleSensorSource(
    override val key: String = "ble_sensor",
    override val name: String = "低功耗手环"
) : CaptureSource {

    override fun readOnce(): Int =
        throw IllegalStateException("BLE 是长连接，请先连接再读")

    override fun parseReply(line: String): Map<String, Int>? {
        val m = Regex("""value\s*=\s*(-?\d+)""").find(line) ?: return null
        val metric = DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
        return mapOf(metric to m.groupValues[1].toInt())
    }
}
