package com.example.physiorecord.capture

import java.util.concurrent.Executors

/**
 * 每台设备一条串行队列。
 *
 * 同一台设备的连接/断开/读/下发必须按顺序；不同设备各走各的队列，互不阻塞。
 */
class DeviceQueue(
    private val key: String,
    private val source: CaptureSource
) {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "device-$key")
    }

    fun submit(
        command: DeviceCommand,
        payload: String? = null,
        deliver: (CommandResult) -> Unit
    ) {
        worker.execute {
            val t0 = System.nanoTime()
            val result = try {
                run(command, payload)
            } catch (e: Exception) {
                CommandResult.Failed(e.message ?: "未知错误")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            android.util.Log.d("Capture", "设备命令 $command 完成：$result，耗时=${ms}ms，队列=$key")
            deliver(result)
        }
    }

    private fun run(command: DeviceCommand, payload: String?): CommandResult {
        return when (command) {
            DeviceCommand.CONNECT -> {
                val session = DeviceHub.session(key)
                    ?: return CommandResult.Failed("这台设备的通道暂不支持连接")
                val st = session.connect()
                if (st.state == LinkState.CONNECTED) CommandResult.Ok()
                else CommandResult.Failed(st.lastError ?: "连接失败")
            }

            DeviceCommand.DISCONNECT -> {
                DeviceHub.session(key)?.disconnect()
                CommandResult.Ok()
            }

            DeviceCommand.READ_ONCE -> {
                val session = DeviceHub.session(key)
                if (session == null) {
                    CommandResult.Ok(source.readMetrics())
                } else {
                    if (session.status.state != LinkState.CONNECTED) {
                        val st = session.connect()
                        if (st.state != LinkState.CONNECTED) {
                            return CommandResult.Failed(st.lastError ?: "连接失败")
                        }
                    }
                    val reply = session.sendLine("READ")
                    val metrics = source.parseReply(reply)
                        ?: throw IllegalStateException("回包格式不对：${reply.take(40)}")
                    CommandResult.Ok(metrics, reply)
                }
            }

            DeviceCommand.SEND_TEXT -> {
                val text = payload?.trim().orEmpty()
                if (text.isEmpty()) return CommandResult.Failed("没有要下发的文本")
                val session = DeviceHub.session(key)
                    ?: return CommandResult.Failed("这台设备的通道不支持下发")
                if (session.status.state != LinkState.CONNECTED) {
                    val st = session.connect()
                    if (st.state != LinkState.CONNECTED) {
                        return CommandResult.Failed(st.lastError ?: "连接失败")
                    }
                }
                try {
                    CommandResult.Ok(raw = session.sendLine(text))
                } catch (e: ReadTimeoutException) {
                    CommandResult.NoReceipt(e.message ?: "读超时")
                }
            }
        }
    }

    fun shutdown() {
        worker.shutdown()
    }
}
