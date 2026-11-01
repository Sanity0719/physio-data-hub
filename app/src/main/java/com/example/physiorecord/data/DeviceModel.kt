package com.example.physiorecord.data

/**
 * 一条“通道”：手机用哪种方式、连到哪个地址。
 *
 * 通道只描述“怎么连”，不描述“连的是谁”。
 * 加一种连接方式 = 加一个种类 + 一个地址类型，设备本身不动。
 */
enum class ChannelKind(val label: String) {
    BUILTIN("本机模拟"),
    WIFI_TCP("WiFi/TCP"),
    WIFI_UDP("WiFi/UDP"),
    CLASSIC_BLUETOOTH("经典蓝牙"),
    BLE("低功耗蓝牙"),
    USB_SERIAL("USB 串口")
}

/** 地址按类型分开存，不塞进一个字符串里（避免用冒号个数猜传输方式）。 */
sealed class ChannelAddress {
    /** 没有外部地址：本机模拟、或由系统自己发现 */
    object None : ChannelAddress()

    /** 网络：主机 + 端口 */
    data class Network(val host: String, val port: Int) : ChannelAddress()

    /** 蓝牙：MAC 地址 */
    data class Bluetooth(val mac: String) : ChannelAddress()

    /** USB：厂商号 + 产品号；串口名在接入时再定 */
    data class Usb(val vendorId: Int, val productId: Int) : ChannelAddress()

    fun describe(): String = when (this) {
        is None -> "本机"
        is Network -> "$host:$port"
        is Bluetooth -> mac
        is Usb -> "VID=%04X PID=%04X".format(vendorId, productId)
    }
}

/** 通道：种类 + 地址 + 一句给人看的备注（例如“随机数，2 秒一次”） */
data class Channel(
    val id: String,
    val kind: ChannelKind,
    val address: ChannelAddress,
    val note: String = ""
)

/**
 * 一台设备：身份归身份，连接归连接。
 *
 * id      —— 数据归属的依据，永不变（表名由它派生）；
 * alias   —— 用户起的名字，可改，不影响数据；
 * model   —— 型号/来源，给人看；
 * channels—— 这台设备可以怎么连；现在只有一条，将来可以多条。
 *
 * 设备名里不出现“蓝牙/串口/WiFi”这类传输词；那些只属于通道。
 */
data class Device(
    val id: String,
    val alias: String,
    val model: String,
    val primaryMetric: String,
    val metrics: List<String>,
    val channels: List<Channel>
) {
    val tableName: String get() = "device_data_$id"

    fun primaryChannel(): Channel = channels.first()

    /** 给界面用的一句话，例如“本机模拟”“WiFi/TCP 192.168.1.5:8899” */
    fun channelSummary(): String {
        val c = primaryChannel()
        val addr = c.address.describe()
        return if (c.kind == ChannelKind.BUILTIN) c.kind.label else "${c.kind.label} $addr"
    }
}
