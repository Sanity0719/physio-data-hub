package com.example.physiorecord.link

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 局域网监听：手机"守在门口"等 PC 连上来，收到一行请求，回一行。
 *
 * 监听必须放在专用线程，不能复用界面线程——否则 accept 会阻塞界面刷新。
 * 只做一件事：收一行、回一行；回什么由调用方传入的 reply 回调决定（默认返回库里最新一笔）。
 */
class LanListener(
    private val port: Int = PORT,
    private val reply: () -> String
) {
    companion object {
        const val PORT = 8888
        private const val TAG = "Lan"
        private const val READ_TIMEOUT_MS = 5000
        private const val MAX_LINE = 1024
    }

    /** 给界面报信的三个口子：状态、一问一答、出错 */
    interface Listener {
        fun onState(text: String)
        fun onRequest(request: String, response: String)
        fun onError(message: String)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null

    fun start(listener: Listener) {
        if (running) {
            listener.onError("已经在监听")
            return
        }
        running = true
        val t = Thread {
            Log.d(TAG, "监听线程启动：线程=${Thread.currentThread().name}")
            val ss = ServerSocket()
            server = ss
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                listener.onState("监听中 · 端口 $port")
                Log.d(TAG, "监听中：端口=$port，线程=${Thread.currentThread().name}")
                while (running) {
                    val c = ss.accept()
                    client = c
                    Log.d(TAG, "收到连接：线程=${Thread.currentThread().name}")
                    c.soTimeout = READ_TIMEOUT_MS
                    val line = readLine(c)
                    if (line == null) {
                        // 连上了但没在 5 秒内给出一整行
                        Log.d(TAG, "等待超时：对方没有发完整一行")
                        listener.onError("等待超时：对方没有发完整一行")
                        try { c.close() } catch (_: Exception) {}
                        continue
                    }
                    Log.d(TAG, "收到请求：$line")
                    val resp = if (line.trim() == "LATEST") reply() else "UNKNOWN"
                    try {
                        c.getOutputStream().write((resp + "\n").toByteArray(Charsets.UTF_8))
                        c.getOutputStream().flush()
                    } catch (e: Exception) {
                        Log.d(TAG, "回话失败：${e.message}")
                    }
                    listener.onRequest(line.trim(), resp)
                    // 一次失败只关当前这条连接，监听继续
                    try { c.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                // 正常停止（stop 关掉了 server/client）不算错误，不往界面报
                if (running) {
                    Log.d(TAG, "监听异常：${e.message}", e)
                    listener.onError(e.message ?: "监听失败")
                } else {
                    Log.d(TAG, "监听停止（关闭时的异常已忽略）")
                }
            } finally {
                try { ss.close() } catch (_: Exception) {}
            }
        }
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        try { client?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        thread?.join(1000)
        thread = null
        Log.d(TAG, "监听已停止")
    }

    /**
     * 读到换行为止（PC 发完按回车）；超时或超长返回 null。
     * Java 里这就是 BufferedReader.readLine() 的手写版——这里手写是为了能设"读超时"。
     */
    private fun readLine(socket: Socket): String? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val sb = StringBuilder()
        return try {
            while (true) {
                val ch = reader.read()
                if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
                if (ch == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(ch.toChar())
                if (sb.length > MAX_LINE) return null
            }
            null
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
