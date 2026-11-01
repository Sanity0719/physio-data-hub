package com.example.physiorecord.export

import com.example.physiorecord.data.PhysioRepository
import java.io.File

/**
 * 一台设备的采集数据与文本表（CSV）互转。
 *
 * 表头固定为 AT,VALUE：
 *   AT    时刻（毫秒）
 *   VALUE 原始值
 *
 * 导入时逐行读；时刻或数值解析不了的行计入"坏行"，不中断整次导入；
 * 同一时刻已经存在的行会被跳过（按设备的时刻唯一），计入"跳过"。
 */
object DeviceCsv {

    const val HEADER = "AT,VALUE"

    /** 导出：返回实际写出的行数（不含表头） */
    fun export(repository: PhysioRepository, deviceKey: String, file: File): Int {
        val rows = repository.recentBetween(deviceKey, 0L, Long.MAX_VALUE)
        file.bufferedWriter().use { w ->
            w.write(HEADER)
            w.newLine()
            for (r in rows) {
                w.write("${r.at},${r.value}")
                w.newLine()
            }
        }
        return rows.size
    }

    data class ImportReport(val imported: Int, val skipped: Int, val bad: Int)

    /** 导入：返回 导入几条 / 跳过几条（同刻已有）/ 坏行几条 */
    fun import(repository: PhysioRepository, deviceKey: String, file: File): ImportReport {
        var imported = 0
        var skipped = 0
        var bad = 0
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line == HEADER) return@forEachLine
            val parts = line.split(',')
            if (parts.size != 2) {
                bad++
                return@forEachLine
            }
            val at = parts[0].trim().toLongOrNull()
            val value = parts[1].trim().toIntOrNull()
            if (at == null || value == null) {
                bad++
                return@forEachLine
            }
            if (repository.save(deviceKey, value, at)) imported++ else skipped++
        }
        return ImportReport(imported, skipped, bad)
    }
}
