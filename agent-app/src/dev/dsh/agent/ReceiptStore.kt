package dev.dsh.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务回执的本地存储。只存摘要，不存屏幕原文、密码或完整 UI 树。
 * 文件放在应用私有目录，DSH 通过 receipts.* 读写。
 */
internal object ReceiptStore {
    private const val FILE = "receipts.jsonl"
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_LINES = 500
    private val lock = Any()

    fun append(
        context: Context,
        taskId: String,
        title: String,
        status: String,
        steps: JSONArray? = null,
        error: String = "",
    ): JSONObject {
        val receipt = JSONObject()
            .put("taskId", taskId)
            .put("title", title)
            .put("status", status)
            .put("at", System.currentTimeMillis())
            .put("steps", steps ?: JSONArray())
        if (error.isNotEmpty()) receipt.put("error", error)
        synchronized(lock) {
            receiptFile(context).appendText(receipt.toString() + "\n")
            trim(context)
        }
        return receipt
    }

    fun list(context: Context, limit: Int = 20): JSONArray {
        val all = synchronized(lock) { readAll(context) }
        val out = JSONArray()
        val start = (all.size - limit.coerceIn(1, 100)).coerceAtLeast(0)
        for (i in start until all.size) out.put(all[i])
        return out
    }

    fun latest(context: Context): JSONObject? = synchronized(lock) {
        readAll(context).lastOrNull()
    }

    fun count(context: Context): Int = synchronized(lock) { readAll(context).size }

    private fun receiptFile(context: Context): File = File(context.filesDir, FILE)

    private fun readAll(context: Context): List<JSONObject> {
        val file = receiptFile(context)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (line.isBlank()) null else try { JSONObject(line) } catch (_: Exception) { null }
            }.takeLast(MAX_LINES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun trim(context: Context) {
        val file = receiptFile(context)
        if (file.length() <= MAX_BYTES) return
        val lines = file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
