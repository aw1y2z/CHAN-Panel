package io.github.aw1y2z.changan.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 官方消息中心（VCS 网关 `/openc-apigw/fc/api/v2/open/information/`）。
 *
 * 协议来自手表端 `ApiService.java` / `MessageClassifyActivity.java`：
 *  - 列表：`actionType=1` + `trackUserId` + `trunkFullPath`（空串=全部）
 *  - 已读：手表端只给了 `infoTrackIds` + `readStatus`，服务端仍报缺参，见 [markRead]
 *
 * 返回是 Spring Page：`data.content[]`、`data.totalElements`（服务端固定 200 条/页）。
 */
object InformationApi {

    private const val PATH_LIST = "/openc-apigw/fc/api/v2/open/information/getReceiverInformations"
    private const val PATH_UPDATE = "/openc-apigw/fc/api/v2/open/information/updateInformationStatus"

    /** 分类过滤值：空串=全部 */
    const val TYPE_ALL = ""

    /**
     * 手表 1.1 消息中心实际用的两个数据源（`trunkFullPath` 前缀级过滤）：
     *  - 点熄火：`APP-CONTROL`，`trackUserId` 用账号 uid
     *  - 安全守护：`APP-SECURITY`，`trackUserId` 用 userInfo 里的 userId
     */
    const val TYPE_CONTROL = "APP-CONTROL"
    const val TYPE_SECURITY = "APP-SECURITY"

    // 旧细分值保留，供历史调用点使用
    const val TYPE_FLAMEOUT = "APP-CONTROL-FLAMEOUT"
    const val TYPE_IGNITION = "APP-CONTROL-IGNITION"
    const val TYPE_GUARD = "APP-GUARD-SMART_GUARD"

    /** 一条官方消息 */
    data class Msg(
        val id: String,
        val stubId: String,
        val title: String,
        val content: String,
        val summary: String,
        val createdAt: String,
        val typeName: String,
        val typePath: String,
        val read: Boolean,
        val jumpParam: String,
    )

    data class Page(val total: Int, val items: List<Msg>)

    /**
     * 拉一页消息。
     * @param startLength 起始偏移（服务端按实际条数算，不吃 displayLength）
     * @param trunkFullPath 分类过滤，[TYPE_ALL] 拿全部
     */
    fun list(
        startLength: Int = 0,
        displayLength: Int = 20,
        trunkFullPath: String = TYPE_ALL,
        trackUserId: String = TokenStore.userId,
    ): Page {
        // 手表 1.1 的请求体还带 realReceiverId=carId，少了它服务端只返回空页
        val form = mapOf(
            "actionType" to 1,
            "trackUserId" to trackUserId,
            "realReceiverId" to TokenStore.carId,
            "trunkFullPath" to trunkFullPath,
            "startLength" to startLength,
            "displayLength" to displayLength,
        )
        val root = JSONObject(ChanganClient.postFormVcs(PATH_LIST, form))
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("msg", "拉取消息失败"))
        }
        val data = root.optJSONObject("data") ?: return Page(0, emptyList())
        val arr = data.optJSONArray("content") ?: return Page(0, emptyList())
        val items = ArrayList<Msg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items += Msg(
                id = o.optString("id"),
                stubId = o.optString("stubId"),
                title = o.optString("title"),
                content = o.optString("content").ifEmpty { o.optString("summary") },
                summary = o.optString("summary"),
                createdAt = o.optString("createdAt"),
                typeName = o.optString("infoTypeName"),
                typePath = o.optString("infoTypeFullPath"),
                read = o.optInt("readStatus", 0) != 0,
                jumpParam = o.optString("jumpParam"),
            )
        }
        return Page(data.optInt("totalElements", items.size), items)
    }

    /** 已命中的参数组合，命中后只发那一种 */
    private var readVariant = -1

    /**
     * 标记若干条已读。
     *
     * 手表端 `readAllMessageByID` 是死代码，照着实现会被服务端判 `40005 缺少必填参数`，
     * 所以这里把「用 id 还是 stubId」「Form 还是 JSON」的组合都试一遍，
     * 第一个 `code==0` 的就记住，后续只发那一种。
     */
    fun markRead(msgs: List<Msg>): Boolean {
        if (msgs.isEmpty()) return true
        val ids = msgs.joinToString(",") { it.id }
        val stubs = msgs.joinToString(",") { it.stubId }
        val idArr = JSONArray().apply { msgs.forEach { put(it.id) } }
        val stubArr = JSONArray().apply { msgs.forEach { put(it.stubId) } }
        val uid = TokenStore.userId
        val carId = TokenStore.carId

        fun form(map: Map<String, Any>): String = ChanganClient.postFormVcs(PATH_UPDATE, map)
        fun json(o: JSONObject): String = ChanganClient.postJsonVcs(PATH_UPDATE, o.toString())

        val variants: List<Pair<String, () -> String>> = listOf(
            // 参数名是单数：infoTrackId + readStatus + trackUserId
            "form 单数 infoTrackId" to {
                form(mapOf("infoTrackId" to ids, "readStatus" to 1, "trackUserId" to uid))
            },
            "form id+read" to { form(mapOf("infoTrackIds" to ids, "readStatus" to 1)) },
            "form id+read+uid" to {
                form(mapOf("infoTrackIds" to ids, "readStatus" to 1, "trackUserId" to uid))
            },
            "form id+read+uid+car" to {
                form(
                    mapOf(
                        "infoTrackIds" to ids, "readStatus" to 1,
                        "trackUserId" to uid, "realReceiverId" to carId,
                    ),
                )
            },
            "form stub+read+uid" to {
                form(mapOf("infoTrackIds" to stubs, "readStatus" to 1, "trackUserId" to uid))
            },
            "json id-array+read" to {
                json(JSONObject().put("infoTrackIds", idArr).put("readStatus", 1))
            },
            "json id-str+read+uid" to {
                json(
                    JSONObject().put("infoTrackIds", ids).put("readStatus", 1)
                        .put("trackUserId", uid),
                )
            },
            "json stub-array+read+uid" to {
                json(
                    JSONObject().put("infoTrackIds", stubArr).put("readStatus", 1)
                        .put("trackUserId", uid),
                )
            },
            "form id-json-str" to {
                form(mapOf("infoTrackIds" to idArr.toString(), "readStatus" to 1))
            },
            "form +infoTypeFullPath" to {
                form(
                    mapOf(
                        "infoTrackIds" to ids, "readStatus" to 1,
                        "infoTypeFullPath" to msgs.first().typePath, "trackUserId" to uid,
                    ),
                )
            },
            "form +readTime" to {
                form(
                    mapOf(
                        "infoTrackIds" to ids, "readStatus" to 1,
                        "readTime" to System.currentTimeMillis(), "trackUserId" to uid,
                    ),
                )
            },
            "form +infoStatus" to {
                form(
                    mapOf(
                        "infoTrackIds" to ids, "readStatus" to 1,
                        "infoStatus" to 1, "trackUserId" to uid,
                    ),
                )
            },
        )

        val order = if (readVariant in variants.indices) {
            listOf(readVariant) + variants.indices.filter { it != readVariant }
        } else {
            variants.indices.toList()
        }
        for (i in order) {
            val (label, call) = variants[i]
            val resp = runCatching { call() }.getOrNull() ?: continue
            val root = runCatching { JSONObject(resp) }.getOrNull() ?: continue
            Log.i("InformationApi", "markRead[$label] -> ${root.optInt("code")} ${root.optString("msg")}")
            if (root.optInt("code", -1) == 0) {
                readVariant = i
                return true
            }
        }
        return false
    }
}
