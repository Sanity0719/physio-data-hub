package com.example.physiorecord.capture

import com.example.physiorecord.data.DeviceRegistry

/** USB 串口设备的数据源：读走长连接会话，这里只保留回包解析。 */
class UsbSensorSource(
    override val key: String = "usb_sensor",
    override val name: String = "USB 采集器"
) : CaptureSource {

    override fun readOnce(): Int =
        throw IllegalStateException("USB 是长连接，请先连接再读")

    override fun parseReply(line: String): Map<String, Int>? {
        val m = Regex("""value\s*=\s*(-?\d+)""").find(line) ?: return null
        val metric = DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
        return mapOf(metric to m.groupValues[1].toInt())
    }
}
