package com.example.physiorecord.capture

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 连接状态：只存在于运行期，由会话对象拥有，不写进设备、不写进偏好。
 */
enum class LinkState(val text: String) {
    IDLE("未连接"),
    CONNECTING("连接中…"),
    CONNECTED("已连接"),
    RETRYING("重试中…"),
    FAILED("连接失败")
}

data class LinkStatus(
    val deviceId: String,
    val channelId: String? = null,
    val state: LinkState = LinkState.IDLE,
    val since: Long = 0L,
    val attempt: Int = 0,
    val lastError: String? = null
) {
    fun line(): String = when (state) {
        LinkState.IDLE -> "○ 未连接"
        LinkState.CONNECTING -> "◐ 连接中…（第 $attempt 次）"
        LinkState.CONNECTED -> "● 已连接 · " + ((System.currentTimeMillis() - since) / 1000) + " 秒"
        LinkState.RETRYING -> "◌ 重试中…（第 $attempt 次）"
        LinkState.FAILED -> "⚠ 连接失败：" + (lastError ?: "未知原因")
    }
}

/** 读回包超时：连接还在，只是这一次没等到回话 */
class ReadTimeoutException(message: String) : Exception(message)

/** 一条通道的会话：连接状态、打开/关闭、发一行读一行。TCP 与经典蓝牙共用这一层。 */
interface LinkSession {
    val status: LinkStatus
    fun connect(): LinkStatus
    fun disconnect()
    fun sendLine(line: String): String
}

/**
 * 一条 TCP 通道的会话：长连接、连接状态、发一行读一行。
 *
 * 收包单独一条读线程：对端一关，readLine 返回 null / 抛错，状态立刻变 FAILED。
 * 发送行之后只在队列里等回话，超时算“读超时”，不拆连接。
 */
class TcpLinkSession(
    private val deviceId: String,
    val channelId: String,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 3000
) : LinkSession {
    @Volatile
    override var status = LinkStatus(deviceId, channelId)
        private set

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val incoming = LinkedBlockingQueue<String>()
    @Volatile
    private var readerThread: Thread? = null
    /** 掉线后自动重连：最多 3 次，每次间隔 1.5 秒；手动断开不重连 */
    @Volatile
    var autoReconnect = true
    @Volatile
    private var manualClose = false
    private var retryCount = 0
    private val retryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "link-retry-$deviceId")
    }

    @Synchronized
    override fun connect(): LinkStatus {
        manualClose = false
        retryCount = 0
        if (status.state == LinkState.CONNECTED) return status
        return doConnect()
    }

    @Synchronized
    private fun doConnect(): LinkStatus {        status = status.copy(
            state = LinkState.CONNECTING,
            since = System.currentTimeMillis(),
            attempt = status.attempt + 1,
            lastError = null
        )
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = 0
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            incoming.clear()
            readerThread = Thread({ readLoop() }, "link-reader-$deviceId").also { it.start() }
            status = status.copy(state = LinkState.CONNECTED, since = System.currentTimeMillis(), lastError = null)
            android.util.Log.d("Link", "device=$deviceId channel=$channelId state=CONNECTED addr=$host:$port")
        } catch (e: Exception) {
            closeQuietly()
            status = status.copy(
                state = LinkState.FAILED,
                since = System.currentTimeMillis(),
                lastError = reason(e)
            )
            android.util.Log.d(
                "Link",
                "device=$deviceId channel=$channelId state=FAILED attempt=${status.attempt} err=${status.lastError}"
            )
            scheduleRetry()
        }
        return status
    }

    /** 掉线后按次数退避重试；次数用完停在 FAILED */
    private fun scheduleRetry() {
        if (!autoReconnect || manualClose) return
        if (retryCount >= MAX_RETRY) return
        retryCount++
        status = status.copy(
            state = LinkState.RETRYING,
            since = System.currentTimeMillis(),
            attempt = retryCount,
            lastError = status.lastError
        )
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=RETRYING attempt=$retryCount")
        retryExecutor.schedule({
            if (!manualClose && status.state == LinkState.RETRYING) doConnect()
        }, RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun readLoop() {
        try {
            val r = reader ?: return
            while (true) {
                val line = r.readLine() ?: break
                incoming.offer(line)
            }
            peerClosed("对端已断开（$host:$port）")
        } catch (e: Exception) {
            if (status.state == LinkState.CONNECTED || status.state == LinkState.CONNECTING) {
                peerClosed(reason(e))
            }
        }
    }

    @Synchronized
    private fun peerClosed(message: String) {
        if (status.state == LinkState.IDLE) return
        closeQuietly()
        status = status.copy(
            state = LinkState.FAILED,
            since = System.currentTimeMillis(),
            lastError = message
        )
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=FAILED err=$message")
        scheduleRetry()
    }

    /** 发一行并等一行回话；回话超时算“读超时”，连接保持 */
    override fun sendLine(line: String): String {
        if (status.state != LinkState.CONNECTED) throw IllegalStateException("未连接")
        val w = writer ?: throw IllegalStateException("未连接")
        w.write(line)
        w.newLine()
        w.flush()
        return incoming.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            ?: throw ReadTimeoutException("读超时（$host:$port）")
    }

    @Synchronized
    override fun disconnect() {
        manualClose = true
        retryCount = 0
        closeQuietly()
        status = status.copy(state = LinkState.IDLE, since = System.currentTimeMillis(), lastError = null)
        android.util.Log.d("Link", "device=$deviceId channel=$channelId state=IDLE")
    }

    private companion object {
        const val MAX_RETRY = 3
        const val RETRY_DELAY_MS = 1500L
    }

    private fun reason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时（$host:$port）"
        is java.net.ConnectException -> "设备拒绝连接（$host:$port）"
        is java.net.UnknownHostException -> "找不到地址（$host）"
        is java.net.SocketException -> "连接已断开（$host:$port）"
        else -> (e.message ?: e.javaClass.simpleName) + "（$host:$port）"
    }

    private fun closeQuietly() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        readerThread?.interrupt()
        readerThread = null
        reader = null
        writer = null
        socket = null
        incoming.clear()
    }
}
