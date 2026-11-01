package com.example.physiorecord.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** 后台干活的公共线程：查库/写库都走这里，结果转回界面线程 */
object Bg {
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-bg") }
    val main = Handler(Looper.getMainLooper())
    fun onMain(block: () -> Unit) = main.post(block)
}
