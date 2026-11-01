package com.example.physiorecord.link

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 主动外发：手机当"发起方"，连到指定地址，发一句、读一句。
 *
 * 与"监听"相反的方向：移动网络下手机通常没有固定的可达地址（运营商 NAT），
 * 所以真正用在移动场景时，必须是手机主动连出去，而不是等别人连它。
 * 默认连到同一网络内一台可达主机（模拟器上为宿主机），用于验证主动外发方向。
 */
object OutboundClient {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    fun send(host: String, port: Int, line: String): String {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().write("$line\n".toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                .readLine() ?: "EMPTY_REPLY"
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
