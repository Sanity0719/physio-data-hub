package com.example.physiorecord.capture

/**
 * 一个设备命令：从统一入口进来，交给某台设备的队列去执行。
 *
 * 新增命令类型（如改配置、升级固件）时在此扩展，
 * 调用方（界面、服务、通路）不用跟着改。
 */
enum class DeviceCommand {
    CONNECT,
    DISCONNECT,
    READ_ONCE,
    SEND_TEXT
}

/** 命令的执行结果：成功（读带指标、下发带回执）、失败（原因）、超时无回执 */
sealed class CommandResult {
    data class Ok(
        val metrics: Map<String, Int> = emptyMap(),
        val raw: String = ""
    ) : CommandResult() {
        /** 兼容只关心一个值的调用方：取第一个指标 */
        val value: Int? get() = metrics.entries.firstOrNull()?.value
    }

    data class Failed(val reason: String) : CommandResult()

    /** 发出去了但没等到回话：结果未知，既不是成功也不是失败 */
    data class NoReceipt(val reason: String) : CommandResult()
}
