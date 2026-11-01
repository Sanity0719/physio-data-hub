package com.example.physiorecord.data

/**
 * 设备登记表（代码里的一份清单）。
 *
 * 每台设备 = 身份（id/别名/型号）+ 一条或多条通道。
 * 加设备只在这里加一行；采集、仓库、界面都不跟着改。
 *
 * 表名一律 device_data_ + id：id 不变，表名就不变；
 * 别名改了也不影响数据归属。
 */
object DeviceRegistry {

    const val DEFAULT_KEY = "phone_sensor"

    private val devices = listOf(
        Device(
            id = DEFAULT_KEY,
            alias = "手机传感器",
            model = "手机内置传感器",
            primaryMetric = "value",
            metrics = listOf("value"),
            channels = listOf(
                Channel(
                    id = "phone-builtin",
                    kind = ChannelKind.BUILTIN,
                    address = ChannelAddress.None,
                    note = "随机数，10 秒一笔"
                )
            )
        ),
        Device(
            id = "demo_band",
            alias = "演示手环",
            model = "模拟手环",
            primaryMetric = "heart_rate",
            metrics = listOf("heart_rate", "spo2", "cadence"),
            channels = listOf(
                Channel(
                    id = "band-builtin",
                    kind = ChannelKind.BUILTIN,
                    address = ChannelAddress.None,
                    note = "随机数，读一次约 2 秒"
                )
            )
        ),
        Device(
            id = "net_gateway",
            alias = "局域网网关",
            model = "TCP 网关设备",
            primaryMetric = "value",
            metrics = listOf("value"),
            channels = listOf(
                Channel(
                    id = "net-tcp",
                    kind = ChannelKind.WIFI_TCP,
                    address = ChannelAddress.Network("127.0.0.1", 8899),
                    note = "模拟器用 adb reverse 转到电脑；真机填电脑局域网地址"
                )
            )
        ),
        Device(
            id = "net_gateway_2",
            alias = "备用网关",
            model = "TCP 网关设备（第二台）",
            primaryMetric = "value",
            metrics = listOf("value"),
            channels = listOf(
                Channel(
                    id = "net-tcp-2",
                    kind = ChannelKind.WIFI_TCP,
                    address = ChannelAddress.Network("127.0.0.1", 8899),
                    note = "用来验证“加一台设备只加一行”"
                )
            )
        ),
        Device(
            id = "spp_sensor",
            alias = "串口模块",
            model = "经典蓝牙串口设备",
            primaryMetric = "value",
            metrics = listOf("value"),
            channels = listOf(
                Channel(
                    id = "spp-rfcomm",
                    kind = ChannelKind.CLASSIC_BLUETOOTH,
                    address = ChannelAddress.Bluetooth("00:00:00:00:00:00"),
                    note = "先配对；真机在连接设置里填它的 MAC"
                )
            )
        ),
        Device(
            id = "ble_sensor",
            alias = "低功耗手环",
            model = "BLE 设备",
            primaryMetric = "value",
            metrics = listOf("value"),
            channels = listOf(
                Channel(
                    id = "ble-gatt",
                    kind = ChannelKind.BLE,
                    address = ChannelAddress.Bluetooth("00:00:00:00:00:00"),
                    note = "真机在连接设置里填它的 MAC；要求设备有一个可写且可通知的特征"
                )
            )
        ),
        Device(
            id = "usb_sensor",
            alias = "USB 采集器",
            model = "USB 串口设备",
            primaryMetric = "value",
            metrics = listOf("value"),
            channels = listOf(
                Channel(
                    id = "usb-serial",
                    kind = ChannelKind.USB_SERIAL,
                    address = ChannelAddress.Usb(0, 0),
                    note = "OTG 接串口芯片；接入时系统会弹授权框"
                )
            )
        )
    )

    /** 界面里自建的设备：启动时从库里读出来，追加在代码登记的设备后面 */
    private val customDevices = ArrayList<Device>()

    fun all(): List<Device> = devices + customDevices

    /** 启动时调用一次；重复调用会先清空再装 */
    fun loadCustom(list: List<Device>) {
        customDevices.clear()
        customDevices.addAll(list.distinctBy { it.id })
    }

    fun addCustom(device: Device) {
        customDevices.removeAll { it.id == device.id }
        customDevices.add(device)
    }

    fun isBuiltIn(key: String): Boolean = devices.any { it.id == key }

    fun default(): Device = devices.first { it.id == DEFAULT_KEY }

    fun byKey(key: String): Device? = (devices + customDevices).firstOrNull { it.id == key }

    /** 取某台设备的采集表名；未登记的设备直接报错，避免写到不存在的表里 */
    fun tableFor(key: String): String =
        byKey(key)?.tableName
            ?: throw IllegalArgumentException("未登记的设备：$key")
}
