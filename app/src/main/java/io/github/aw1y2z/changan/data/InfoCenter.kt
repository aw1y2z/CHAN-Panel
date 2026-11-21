package io.github.aw1y2z.changan.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 信息中心：把「车况里能看出来的疏忽」和「点火 / 熄火」落成本机消息。
 *
 * 为什么不是官方消息：官方 App 的消息中心走 `m.iov /appserver/api/information/…`
 * （引力域 H5 插件 `cn-com.changan.cordova.plugin.messagecenter`），但本地抓包记录里
 * 路径被截断成 `…`，手表端 `ApiService` 也没有这组接口，参数无法确认。
 * 所以这里改为「本机车况检测」，产出同类的两类消息，且完全离线：
 * - 安全守护：未锁车、车窗 / 天窗 / 后备箱 / 引擎盖未关、胎压异常、电瓶亏电、钥匙电量低
 * - 点熄火：`engineStatus`（1=启动 2=熄火）发生变化时记一条
 *
 * 状态跃迁才提醒：正常 → 异常时才生成一条，异常持续不会反复刷屏；
 * 同类消息 30 分钟内去重，最多保留 [MAX_KEEP] 条。
 */
object InfoCenter {

    const val KIND_SAFETY = "safety"
    const val KIND_IGNITION = "ignition"

    /** 车况字段没上报时的统一缺省值，与 Dashboard 里的判定保持一致 */
    private const val MAX_KEEP = 120
    private const val REPEAT_WINDOW_MS = 30 * 60 * 1000L

    data class Message(
        val id: Long,
        val kind: String,
        val title: String,
        val detail: String,
        val time: Long,
        val read: Boolean,
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "info_center.json")

    private fun load(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrElse { JSONObject() }

    private fun save(ctx: Context, root: JSONObject) {
        runCatching { file(ctx).writeText(root.toString()) }
    }

    /** 全部消息，新的在前 */
    fun messages(ctx: Context): List<Message> {
        synchronized(this) {
            val arr = load(ctx).optJSONArray("items") ?: return emptyList()
            val out = ArrayList<Message>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += Message(
                    id = o.optLong("id"),
                    kind = o.optString("kind", KIND_SAFETY),
                    title = o.optString("title"),
                    detail = o.optString("detail"),
                    time = o.optLong("time"),
                    read = o.optBoolean("read", false),
                )
            }
            return out.sortedByDescending { it.time }
        }
    }

    fun unreadCount(ctx: Context): Int = messages(ctx).count { !it.read }

    /** 按类别统计未读（点火熄火 / 安全守护各自的角标） */
    fun unreadCount(ctx: Context, kind: String): Int =
        messages(ctx).count { !it.read && it.kind == kind }

    fun markAllRead(ctx: Context) {
        synchronized(this) {
            val root = load(ctx)
            val arr = root.optJSONArray("items") ?: return
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.put("read", true)
            }
            root.put("items", arr)
            save(ctx, root)
        }
    }

    fun clear(ctx: Context) {
        synchronized(this) {
            // 只清消息，保留 last 快照，否则下一次刷新会把当前异常再提醒一遍
            val root = load(ctx)
            root.put("items", JSONArray())
            save(ctx, root)
        }
    }

    /**
     * 用一份车况快照做检测。放在 IO 线程调用，内部串行化，重复调用安全。
     * 首次调用只建立基线，不产生「启动 / 熄火」消息（那时没有可比的上一次状态）。
     */
    fun observe(ctx: Context, status: JSONObject): List<Message> = synchronized(this) {
            val root = load(ctx)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }

            val last = root.optJSONObject("last") ?: JSONObject()
            val now = System.currentTimeMillis()
            val fresh = ArrayList<Triple<String, String, String>>()

            // ---------------- 安全守护：正常 → 异常时才记一条 ----------------
            fun check(key: String, bad: Boolean, title: String, detail: String) {
                if (!bad) {
                    last.put(key, false)
                    return
                }
                if (!last.optBoolean(key, false)) fresh += Triple(KIND_SAFETY, title, detail)
                last.put(key, true)
            }

            val locked = status.optInt("passengerDoorLock", -1)
            check("lock", locked > 0, "车辆未上锁", "远程锁车，或确认钥匙已随身带走")

            val winClosed = windowsClosed(status)
            check("window", winClosed == false, "车窗未关", "下雨或过夜前建议远程关窗")

            val sunroof = status.optInt("sunroof", -1)
            check("sunroof", sunroof > 0, "天窗未关", "天窗开着容易进水，建议远程关闭")

            val trunk = status.optInt("trunk", -1)
            check("trunk", trunk > 0, "后备箱未关", "确认行李已放置好并关闭尾门")

            val hood = status.optInt("hood", -1)
            check("hood", hood > 0, "引擎盖未关", "引擎盖未关会影响行车，请立即检查")

            val tyres = ArrayList<String>()
            fun tyre(name: String, v: Int) {
                when {
                    v in 1..179 -> tyres += "$name $v kPa 偏低"
                    v > 320 -> tyres += "$name $v kPa 偏高"
                }
            }
            tyre("左前", status.optInt("lfTyrePressure", 0))
            tyre("右前", status.optInt("rfTyrePressure", 0))
            tyre("左后", status.optInt("lrTyrePressure", 0))
            tyre("右后", status.optInt("rrTyrePressure", 0))
            check("tyre", tyres.isNotEmpty(), "胎压异常", tyres.joinToString("，"))

            val battery = status.optDouble("batteryVoltage", 0.0)
            check(
                "battery",
                battery in 0.1..11.7,
                "电瓶电压偏低",
                "%.1f V，长时间停放可能打不着火".format(battery),
            )

            check(
                "key",
                status.optInt("keyLowPower", -1) > 0,
                "车钥匙电量低",
                "尽快更换钥匙电池，避免无法感应解锁",
            )

            // ---------------- 点熄火 ----------------
            val engine = status.optInt("engineStatus", -1)
            if (engine == 1 || engine == 2) {
                val prev = last.optInt("engine", -1)
                if (prev != engine) {
                    if (prev != -1) {
                        val title = if (engine == 1) "车辆已启动" else "车辆已熄火"
                        val detail = if (engine == 1) {
                            "发动机开始运转，若非本人操作请及时确认"
                        } else {
                            "发动机已停止，本次行程结束"
                        }
                        fresh += Triple(KIND_IGNITION, title, detail)
                    }
                    last.put("engine", engine)
                }
            }

            // ---------------- 落盘 ----------------
            var nextId = items.maxOfOrNull { it.optLong("id") } ?: 0L
            val added = ArrayList<Message>()
            for ((kind, title, detail) in fresh) {
                val dup = items.any {
                    it.optString("title") == title && now - it.optLong("time") < REPEAT_WINDOW_MS
                }
                if (dup) continue
                nextId++
                items += JSONObject()
                    .put("id", nextId)
                    .put("kind", kind)
                    .put("title", title)
                    .put("detail", detail)
                    .put("time", now)
                    .put("read", false)
                added += Message(nextId, kind, title, detail, now, false)
            }

            val kept = items.sortedByDescending { it.optLong("time") }.take(MAX_KEEP)
            val outArr = JSONArray()
            kept.forEach { outArr.put(it) }
            root.put("items", outArr)
            root.put("last", last)
            save(ctx, root)
            added
    }

    /** 车窗是否全关；缺字段跳过，全缺时返回 null */
    private fun windowsClosed(status: JSONObject): Boolean? {
        val keys = listOf(
            "diverWindow", "passengerWindow", "leftFrontDoorrWindow",
            "leftRearDoorrWindow", "rightFrontDoorrWindow", "rightRearWindow",
        )
        var seen = false
        for (k in keys) {
            val v = status.optInt(k, -1)
            if (v < 0) continue
            seen = true
            if (v != 0) return false
        }
        return if (seen) true else null
    }
}
