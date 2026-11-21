package io.github.aw1y2z.changan.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 远程操作记录（本机）。
 *
 * 官方 `/appserver/api/car/getControlActionHistory` 目前对任何组合都返回
 * 500（`内部服务器错误`），拿不到服务端记录，所以这里记录**本 App 下发的每一条
 * 远控指令**：动作名、时间、成功/失败与失败原因，展示形式对齐官方「操作记录」。
 */
object ControlLog {

    private const val FILE_NAME = "control_log.json"
    private const val MAX_KEEP = 200

    data class Entry(
        val action: String,
        val time: Long,
        val ok: Boolean,
        val message: String,
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    private fun load(ctx: Context): JSONArray =
        runCatching { JSONObject(file(ctx).readText()).optJSONArray("items") }.getOrNull()
            ?: JSONArray()

    /** 记录一条远控指令结果 */
    fun add(ctx: Context, action: String, ok: Boolean, message: String) {
        synchronized(this) {
            val current = load(ctx)
            val out = JSONArray()
            out.put(
                JSONObject()
                    .put("a", action)
                    .put("t", System.currentTimeMillis())
                    .put("ok", ok)
                    .put("m", message),
            )
            for (i in 0 until minOf(current.length(), MAX_KEEP - 1)) {
                current.optJSONObject(i)?.let { out.put(it) }
            }
            runCatching { file(ctx).writeText(JSONObject().put("items", out).toString()) }
        }
    }

    /** 全部记录，新的在前 */
    fun list(ctx: Context): List<Entry> {
        synchronized(this) {
            val arr = load(ctx)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += Entry(
                    action = o.optString("a"),
                    time = o.optLong("t"),
                    ok = o.optBoolean("ok", false),
                    message = o.optString("m"),
                )
            }
            return out
        }
    }
}
