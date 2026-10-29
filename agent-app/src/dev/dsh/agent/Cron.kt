package dev.dsh.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

internal class Cron private constructor(
    private val fields: Array<IntArray>,
    private val anyDay: Boolean,
    private val anyWeekday: Boolean
) {
    fun next(epochMilli: Long, zone: ZoneId): Long {
        var time = Instant.ofEpochMilli(epochMilli).atZone(zone).withSecond(0).withNano(0).plusMinutes(1)
        repeat(366 * 24 * 60 * 5) {
            val day = contains(fields[2], time.dayOfMonth)
            val weekday = contains(fields[4], time.dayOfWeek.value % 7)
            val dayMatches = if (anyDay && anyWeekday || anyDay) weekday else if (anyWeekday) day else day || weekday
            if (contains(fields[3], time.monthValue) && dayMatches &&
                contains(fields[1], time.hour) && contains(fields[0], time.minute)) {
                return time.toInstant().toEpochMilli()
            }
            time = time.plusMinutes(1)
        }
        throw IllegalArgumentException("cron has no time in the next five years")
    }

    companion object {
        @JvmStatic
        fun parse(expression: String): Cron {
            val parts = expression.trim().split(Regex("""\s+"""))
            if (parts.size != 5) throw IllegalArgumentException("cron requires five fields")
            val min = intArrayOf(0, 0, 1, 1, 0)
            val max = intArrayOf(0, 23, 31, 12, 7)
            val fields = Array(5) { parseField(parts[it], min[it], max[it]) }
            return Cron(fields, parts[2] == "*", parts[4] == "*")
        }

        private fun parseField(text: String, min: Int, max: Int): IntArray {
            val allowed = BooleanArray(max + 1)
            for (part in text.split(",", ignoreCase = false)) {
                val stepParts = part.split("/", ignoreCase = false)
                if (stepParts.size > 2) throw IllegalArgumentException("invalid cron field")
                val step = if (stepParts.size == 2) number(stepParts[1], 1, 10000) else 1
                var start = min
                var end = max
                if (stepParts[0] != "*") {
                    val range = stepParts[0].split("-", ignoreCase = false)
                    if (range.size > 2) throw IllegalArgumentException("invalid cron range")
                    start = number(range[0], min, max)
                    end = if (range.size == 2) number(range[1], start, max) else start
                }
                var value = start
                while (value <= end) {
                    allowed[value] = true
                    value += step
                }
            }
            var count = 0
            for (value in allowed) if (value) count++
            if (count == 0) throw IllegalArgumentException("empty cron field")
            val result = IntArray(count)
            var index = 0
            for (i in allowed.indices) if (allowed[i]) result[index++] = i
            return result
        }

        private fun number(text: String, min: Int, max: Int): Int {
            val value = try {
                Integer.parseInt(text)
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("invalid cron number")
            }
            if (value < min || value > max) throw IllegalArgumentException("cron value out of range")
            return value
        }

        private fun contains(values: IntArray, wanted: Int): Boolean = values.any { it == wanted }
    }
}
