package io.github.aw1y2z.changan.data

import android.content.Context
import org.json.JSONArray

/**
 * 记录哪些官方消息已经推送过。
 *
 * 不能用服务端 `readStatus` 判断：用户可能在引力域 App 里读过，
 * 但本机还没推过通知，那样会漏提醒。所以单独记一份「本机已推送 id」。
 */
object PushState {

    private const val PREF = "push_state"
    private const val KEY = "pushed_ids"
    private const val MAX = 300

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load(ctx: Context): MutableList<String> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return ArrayList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    /** 没推过就记下并返回 true；推过返回 false */
    fun markPushed(ctx: Context, id: String): Boolean {
        if (id.isEmpty()) return false
        synchronized(this) {
            val list = load(ctx)
            if (list.contains(id)) return false
            list += id
            val trimmed = if (list.size > MAX) list.subList(list.size - MAX, list.size) else list
            prefs(ctx).edit().putString(KEY, JSONArray(trimmed).toString()).apply()
            return true
        }
    }
}
