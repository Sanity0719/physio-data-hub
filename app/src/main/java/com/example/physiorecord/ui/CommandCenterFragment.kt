package com.example.physiorecord.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.physiorecord.capture.CommandResult
import com.example.physiorecord.capture.DeviceCommand
import com.example.physiorecord.capture.DeviceHub
import com.example.physiorecord.data.PhysioRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置下发：给当前设备发一条文本命令，并把每一次下发记成一行历史。
 *
 * 状态机：PENDING（已排队）→ APPLIED（设备回了话）/ REJECTED（连接或发送失败）/
 * NO_RECEIPT（发出去了但没等到回话，结果未知）。
 */
class CommandCenterFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var repository: PhysioRepository
    private lateinit var payloadInput: EditText
    private lateinit var statusText: TextView
    private lateinit var historyBox: LinearLayout
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(requireContext())
        repository = PhysioRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 12f),
                UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        }
        scroll.addView(box)

        box.addView(android.widget.Button(context).apply {
            text = "← 返回数据管理"
            setOnClickListener { (activity as? com.example.physiorecord.MainActivity)?.closePage() }
        })
        box.addView(UiKit.title(context, "配置下发", 24f))
        box.addView(UiKit.hint(context, "给当前设备发一条文本命令（例如 SET 1）。回话原样记进历史；没回话记 NO_RECEIPT，不当成功。"))

        box.addView(UiKit.label(context, "命令内容"))
        payloadInput = EditText(context).apply {
            setText("SET 1")
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.DIM)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(payloadInput)

        box.addView(android.widget.Button(context).apply {
            text = "下发"
            setOnClickListener { send() }
        })

        statusText = UiKit.text(context, "尚未下发", 12f, UiKit.DIM)
        box.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        box.addView(UiKit.label(context, "下发历史"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 14f) })
        historyBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(historyBox)

        return scroll
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
    }

    private fun send() {
        val text = payloadInput.text.toString().trim()
        if (text.isEmpty()) {
            statusText.text = "命令不能为空"
            return
        }
        val key = prefs.currentDevice
        statusText.text = "已排队，正在下发…"
        Bg.io.execute {
            val id = repository.insertCommand(key, text)
            DeviceHub.submit(key, DeviceCommand.SEND_TEXT, text) { r ->
                val (status, reason, receipt) = when (r) {
                    is CommandResult.Ok -> Triple("APPLIED", null as String?, r.raw)
                    is CommandResult.Failed -> Triple("REJECTED", r.reason, null as String?)
                    is CommandResult.NoReceipt -> Triple("NO_RECEIPT", r.reason, null as String?)
                }
                Bg.io.execute {
                    repository.updateCommand(id, status, reason, receipt)
                    Bg.onMain {
                        statusText.text = when (status) {
                            "APPLIED" -> "已下发并收到回执：${receipt?.take(60)}"
                            "NO_RECEIPT" -> "已发出，但没有回话：$reason（结果未知）"
                            else -> "下发失败：$reason"
                        }
                        refreshHistory()
                    }
                }
            }
        }
    }

    private fun refreshHistory() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val rows = repository.recentCommands(key, 20)
            Bg.onMain {
                historyBox.removeAllViews()
                if (rows.isEmpty()) {
                    historyBox.addView(UiKit.hint(requireContext(), "还没有下发记录"))
                    return@onMain
                }
                for (row in rows) {
                    val detail = when (row.status) {
                        "APPLIED" -> "回执：${row.receipt ?: ""}"
                        "REJECTED" -> "原因：${row.reason ?: ""}"
                        "NO_RECEIPT" -> "无回话：${row.reason ?: ""}"
                        else -> "等待中"
                    }
                    historyBox.addView(UiKit.text(
                        requireContext(),
                        "${fmt.format(Date(row.createdAt))} · ${row.status} · ${row.payload}\n$detail",
                        12f, UiKit.DIM
                    ))
                }
            }
        }
    }
}
