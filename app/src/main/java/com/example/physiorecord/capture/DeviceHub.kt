package com.example.physiorecord.capture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 设备命令的唯一入口。
 *
 * 界面、采集服务、局域网通路，都往这里提交命令，拿到的结果都从同一条回调线回来：
 *   - 每条命令只按设备编号找队列，不关心背后是手机传感器、蓝牙还是串口；
 *   - 每台设备一条串行队列（见 DeviceQueue）；
 *   - 所有回调都排到一条回调线（callbackLine），调用方自己决定要不要再转去界面线。
 *
 * 这样加设备只登记一个数据源，加命令只加一个枚举值，调用方不变。
 */
object DeviceHub {

    private val queues = ConcurrentHashMap<String, DeviceQueue>()
    private val sources = ConcurrentHashMap<String, CaptureSource>()
    private val sessions = ConcurrentHashMap<String, LinkSession>()
    private val tcpOverrides = ConcurrentHashMap<String, Pair<String, Int>>()
    private val sppOverrides = ConcurrentHashMap<String, String>()
    private val bleOverrides = ConcurrentHashMap<String, String>()

    @Volatile
    private var appContext: android.content.Context? = null

    /** BLE 会话要用 Context 才能 connectGatt；在入口初始化一次 */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** 界面里改过 BLE MAC 后，用这份参数建会话 */
    fun configureBle(deviceKey: String, mac: String) {
        bleOverrides[deviceKey] = mac
    }

    /** 界面里改过地址/端口后，用这份参数建会话（没有覆盖时用登记表里的地址） */
    fun configureTcp(deviceKey: String, host: String, port: Int) {
        tcpOverrides[deviceKey] = host to port
    }

    /** 界面里改过蓝牙 MAC 后，用这份参数建会话 */
    fun configureSpp(deviceKey: String, mac: String) {
        sppOverrides[deviceKey] = mac
    }

    /** 关掉并丢掉这台设备的会话；下次连接按最新参数重建 */
    fun resetSession(deviceKey: String) {
        sessions.remove(deviceKey)?.disconnect()
    }

    /** 回调线：所有结果从这里出去，串行，不占用设备队列 */
    private val callbackLine = Executors.newSingleThreadExecutor { r ->
        Thread(r, "device-callback")
    }

    /** 登记一台设备的数据源（可重复调用，后到的覆盖先到的） */
    fun register(source: CaptureSource) {
        sources[source.key] = source
    }

    /** 提交命令；结果在回调线上送达。payload 只有 SEND_TEXT 用得上 */
    fun submit(
        deviceKey: String,
        command: DeviceCommand,
        payload: String? = null,
        onResult: (CommandResult) -> Unit
    ) {
        val source = sources[deviceKey]
        if (source == null) {
            callbackLine.execute { onResult(CommandResult.Failed("未登记的设备：$deviceKey")) }
            return
        }
        val queue = queues.computeIfAbsent(deviceKey) { DeviceQueue(deviceKey, source) }
        queue.submit(command, payload) { result ->
            callbackLine.execute { onResult(result) }
        }
    }

    /** 这台设备的会话；没有可连接的通道就返回 null */
    fun session(deviceKey: String): LinkSession? {
        sessions[deviceKey]?.let { return it }
        val device = com.example.physiorecord.data.DeviceRegistry.byKey(deviceKey) ?: return null
        val channel = device.channels.firstOrNull {
            it.kind == com.example.physiorecord.data.ChannelKind.WIFI_TCP ||
                it.kind == com.example.physiorecord.data.ChannelKind.CLASSIC_BLUETOOTH ||
                it.kind == com.example.physiorecord.data.ChannelKind.BLE ||
                it.kind == com.example.physiorecord.data.ChannelKind.USB_SERIAL
        } ?: return null
        val created: LinkSession = when (channel.kind) {
            com.example.physiorecord.data.ChannelKind.WIFI_TCP -> {
                val addr = channel.address as? com.example.physiorecord.data.ChannelAddress.Network
                    ?: return null
                val override = tcpOverrides[deviceKey]
                TcpLinkSession(
                    deviceKey, channel.id,
                    override?.first ?: addr.host,
                    override?.second ?: addr.port
                )
            }
            com.example.physiorecord.data.ChannelKind.CLASSIC_BLUETOOTH -> {
                val addr = channel.address as? com.example.physiorecord.data.ChannelAddress.Bluetooth
                    ?: return null
                SppLinkSession(
                    deviceKey, channel.id,
                    sppOverrides[deviceKey] ?: addr.mac
                )
            }
            else -> {
                val addr = channel.address as? com.example.physiorecord.data.ChannelAddress.Bluetooth
                val context = appContext ?: return null
                if (channel.kind == com.example.physiorecord.data.ChannelKind.USB_SERIAL) {
                    UsbLinkSession(context, deviceKey, channel.id)
                } else {
                    addr ?: return null
                    BleLinkSession(
                        context, deviceKey, channel.id,
                        bleOverrides[deviceKey] ?: addr.mac
                    )
                }
            }
        }
        sessions[deviceKey] = created
        return created
    }

    /** 界面只读这个，不自己推断 */
    fun linkStatus(deviceKey: String): LinkStatus =
        session(deviceKey)?.status ?: LinkStatus(deviceKey)

    /** 进程退出前（或测试收尾）关掉各设备队列与回调线 */
    fun shutdown() {
        queues.values.forEach { it.shutdown() }
        queues.clear()
        callbackLine.shutdown()
    }
}
