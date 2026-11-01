package com.example.physiorecord.data

/**
 * 最小公式求值：数字、指标名、+ - * /、括号。
 *
 * 变量名就是读数里的指标名（如 heart_rate、spo2）；指标名写错或除零返回 null，
 * 由界面显示“算不了”，不抛异常、不猜。
 */
object Formula {

    fun eval(expr: String, vars: Map<String, Int>): Double? =
        evalDouble(expr, vars.mapValues { it.value.toDouble() })

    fun evalDouble(expr: String, vars: Map<String, Double>): Double? = try {
        val p = Parser(expr, vars)
        val v = p.expr()
        if (p.pos != p.src.length) null else v
    } catch (e: Exception) {
        null
    }

    /**
     * 饼图用的聚合公式：sum(expr) 或 avg(expr)，对窗口内每条读数各算一次再求和/平均。
     * 一条聚合公式只产生一个量（一块饼）。
     */
    fun aggregate(expr: String, readings: List<Map<String, Int>>): Double? {
        val e = expr.trim()
        val m = Regex("^(sum|avg)\\s*\\((.*)\\)$", RegexOption.IGNORE_CASE).find(e) ?: return null
        val kind = m.groupValues[1].lowercase()
        val inner = m.groupValues[2]
        val vals = readings.mapNotNull { eval(inner, it) }
        if (vals.isEmpty()) return null
        return if (kind == "sum") vals.sum() else vals.average()
    }

    private class Parser(val src: String, val vars: Map<String, Double>) {
        var pos = 0

        fun ws() { while (pos < src.length && src[pos].isWhitespace()) pos++ }
        fun peek(): Char { ws(); return if (pos < src.length) src[pos] else '\u0000' }

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = factor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= factor() }
                    '/' -> { pos++; v /= factor() }
                    else -> return v
                }
            }
        }

        fun factor(): Double {
            val c = peek()
            if (c == '(') { pos++; val v = expr(); if (peek() == ')') pos++; return v }
            if (c == '-') { pos++; return -factor() }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                return src.substring(start, pos).toDouble()
            }
            if (c.isLetter() || c == '_') {
                val start = pos
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '.')) pos++
                val name = src.substring(start, pos)
                return vars[name]?.toDouble() ?: throw IllegalArgumentException(name)
            }
            throw IllegalArgumentException("bad char")
        }
    }
}
