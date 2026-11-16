package io.github.aw1y2z.changan.data

import org.json.JSONObject

/**
 * 车控指令与车况（逆向自长安 inCall 手表端）。
 *
 * 唯一通道 VRTM（VCS-HMAC 签名，免 PIN）：
 *   POST /openc-apigw/vrtm-agent/api/v2/open/car-control/{path}
 *     body: {"mapArgsJson","senderType":"APP","cmd","carId"} → data:{"taskId":"..."}
 *   GET  /openc-apigw/vrtm-agent/api/v2/open/car-control/get-control-result?taskId=
 *     code 0 + data.cmdStatus: initialized/running/completed（通常 3 秒内 completed）
 *     车端回执可能是 failure + "车辆已处于关闭发动机状态"，这种视为目标已达成。
 *
 * OSCAR 通道（`openService/oscarcontrol/...`）不可用：网关固定返回
 * {"status_code":100,"error_msg":"证书错误"}，故后备箱改走 VRTM `openTrunk`。
 */
enum class ControlAction(val label: String, val cmd: String, val path: String, val args: String = "{}") {
    LOCK("锁车", "LockDoor", "lockDoor"),
    UNLOCK("解锁", "UnLockDoor", "unLockDoor"),
    REMOTE_START("远程启动", "OpenEngine", "openEngine"),
    REMOTE_STOP("熄火", "CloseEngine", "closeEngine"),
    OPEN_WINDOWS("开车窗", "OpenWindow", "openWindow"),
    CLOSE_WINDOWS("关车窗", "CloseWindow", "closeWindow"),
    WINDOW_SLIT("车窗微开", "WindowSlit", "windowSlit"),
    OPEN_SUNROOF("开天窗", "OpenDormer", "openDormer"),
    CLOSE_SUNROOF("关天窗", "CloseDormer", "closeDormer"),
    HORN_WHISTLE("鸣笛", "Whistle", "whistle"),
    FLASH_LIGHT("闪灯", "FlashLight", "flashLight"),
    SEARCH_CAR("寻车(鸣笛闪灯)", "RemoteSearchCar", "remoteSearchCar"),
    OPEN_AIR("开空调", "OpenAir", "openAir"),
    CLOSE_AIR("关空调", "CloseAir", "closeAir"),
    OPEN_TRUNK("开后备箱", "OpenTrunk", "openTrunk"),
    CLOSE_TRUNK("关后备箱", "CloseTrunk", "closeTrunk"),
}

object ControlActions {

    data class Outcome(val ok: Boolean, val message: String, val raw: String = "")

    private const val VRTM = "/openc-apigw/vrtm-agent/api/v2/open/car-control"
    private const val POLL_INTERVAL_MS = 3000L
    private const val POLL_TIMEOUT_MS = 60_000L

    suspend fun send(action: ControlAction, temperature: String? = null): Outcome {
        val args = if (action.cmd == "OpenAir" && temperature != null) {
            JSONObject().put("temperature", temperature).toString()
        } else {
            action.args
        }
        return sendVrtm(action, args)
    }

    // ---------- 路由一：VRTM ----------

    private fun sendVrtm(action: ControlAction, args: String): Outcome {
        val path = "$VRTM/${action.path}"
        val body = JSONObject()
            .put("mapArgsJson", args)
            .put("senderType", "APP")
            .put("cmd", action.cmd)
            .put("carId", TokenStore.carId)
        val resp = ChanganClient.postJsonVcs(path, body.toString())
        val root = JSONObject(resp)
        val code = root.optInt("code", -1)
        if (code != 0) {
            return Outcome(false, "「${action.label}」下发失败($code)：${root.optString("msg")}", resp)
        }
        val taskId = when (val d = root.opt("data")) {
            is JSONObject -> d.optString("taskId")
            is String -> d
            else -> ""
        }
        if (taskId.isEmpty()) return Outcome(false, "「${action.label}」未返回 taskId", resp)
        return pollVrtm(action, taskId)
    }

    private fun pollVrtm(action: ControlAction, taskId: String): Outcome {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var first = true
        while (System.currentTimeMillis() < deadline) {
            if (!first) Thread.sleep(POLL_INTERVAL_MS)
            first = false
            val resp = ChanganClient.getVcs("$VRTM/get-control-result", mapOf("taskId" to taskId))
            val root = runCatching { JSONObject(resp) }.getOrElse { return Outcome(false, "结果解析失败", resp) }
            if (root.optInt("code", -1) != 0) {
                // 刚下发时可能短暂 40000，继续轮询
                if (root.optInt("code", -1) == 3002) continue
                return Outcome(false, "「${action.label}」查询失败(${root.optInt("code")}): ${root.optString("msg")}", resp)
            }
            val dataJson = parseData(root.opt("data")) ?: continue
            when (dataJson.optString("cmdStatus").lowercase()) {
                "initialized", "running" -> continue
                "completed" -> return Outcome(true, "「${action.label}」执行成功", resp)
                else -> {
                    val msg = dataJson.optString("msg")
                    // 车端回执「车辆已处于关闭发动机状态」这类，语义上目标已达成，不算失败
                    if (msg.contains("已处于") || msg.contains("已经处于") || msg.contains("已经是")) {
                        return Outcome(true, "「${action.label}」$msg", resp)
                    }
                    return Outcome(false, "「${action.label}」执行失败：$msg（${dataJson.optString("cmdStatus")}）", resp)
                }
            }
        }
        return Outcome(false, "「${action.label}」执行超时")
    }

    private fun parseData(data: Any?): JSONObject? {
        val json: String = when (data) {
            is String -> data.replace("\\", "").replace("\"{", "{").replace("}\"", "}")
            is JSONObject -> data.toString()
            else -> return null
        }
        return runCatching { JSONObject(json) }.getOrNull()
    }

    // ---------- 车况 ----------

    /** POST /openc-apigw/shtu/api/v2/open/car/get-car-condition-map（Form: carId） */
    suspend fun fetchStatus(): Result<JSONObject> {
        val resp = ChanganClient.postFormVcs(
            "/openc-apigw/shtu/api/v2/open/car/get-car-condition-map",
            mapOf("carId" to TokenStore.carId),
        )
        val root = JSONObject(resp)
        val code = root.optInt("code", -1)
        if (code != 0) {
            return Result.failure(IllegalStateException("车况查询失败($code)：${root.optString("msg")}"))
        }
        return Result.success(root.optJSONObject("data") ?: JSONObject())
    }
}
