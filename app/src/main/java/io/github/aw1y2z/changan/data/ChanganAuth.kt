package io.github.aw1y2z.changan.data

import org.json.JSONObject

/**
 * 登录/鉴权（逆向自长安 inCall 手表端 v1.0.3）。
 *
 * 登录方式为「二维码授权」：本端生成二维码 → 用户用引力域 App（或长安汽车 App）扫码确认
 * → 轮询到 authorized_code → /oauth/token 换 access_token + secret_key。
 * 无需账号密码，token 体系与手机 App 独立。
 *
 * 流程：
 *  1. POST /oauth/api/qrcode/generate（表单+sign）→ 响应体 PNG 二维码，响应头 qrcode_id
 *  2. GET  /oauth/api/qrcode/getQrcodeStatusNoBackend → result: scanned / unauthorized / authorized{code}
 *  3. POST /oauth/token grant_type=authorization_code → access_token/refresh_token/secret_key/uid/expires_in
 *  4. 刷新：POST /oauth/token grant_type=refresh_token
 */
object ChanganAuth {

    private const val INCALL_HOST = "https://incallapi.changan.com.cn"

    sealed class LoginResult {
        data class Ok(val userId: String) : LoginResult()
        data class Fail(val message: String) : LoginResult()
    }

    /** 第 1 步：请求二维码。返回 qrcodeId + PNG 字节 */
    fun requestQrCode(): Pair<String, ByteArray> {
        val params = mapOf<String, Any>(
            "client_id" to ChanganSign.CLIENT_ID,
            "device_id" to DeviceId.fullId(),
            "size" to "L",
            "state" to "OPPOWATCH",
        )
        val (id, png) = ChanganClient.postFormForBytes(
            "/oauth/api/qrcode/generate",
            params,
            extra = mapOf("appCode" to "usercenter"),
        )
        if (id.isNullOrEmpty() || png == null) {
            throw IllegalStateException("qrcode_id 或二维码图片为空")
        }
        return id to png
    }

    /**
     * 第 2 步：轮询扫码状态。
     * @return "scanned" / "unauthorized" / "authorized"（authorized 时已换取 token）
     */
    fun pollQrStatus(qrcodeId: String): String {
        val resp = ChanganClient.getSigned(
            "/oauth/api/qrcode/getQrcodeStatusNoBackend",
            mapOf("qrcodeId" to qrcodeId),
        )
        val root = JSONObject(resp)
        if (root.optInt("code", -1) != 0) return "unauthorized"
        // 官方这里 data 是被转义的 JSON 字符串："{\"result\":\"unauthorized\"}"
        val data = when (val d = root.opt("data")) {
            is JSONObject -> d
            is String -> runCatching { JSONObject(d.replace("\\", "")) }.getOrNull()
            else -> null
        } ?: return "unauthorized"
        when (data.optString("result")) {
            "authorized" -> {
                val authCode = data.optString("code")
                val token = exchangeToken(authCode)
                return if (token) "authorized" else "token_fail"
            }
            "scanned" -> return "scanned"
            else -> return "unauthorized"
        }
    }

    /**
     * **免扫码授权**：用手机号登录（[XiaoanApi]）拿到的 m.iov token 自助完成授权，换 VCS token。
     *
     * `POST incallapi.changan.com.cn/oauth/api/qrcode/authorize`（表单 `token` + `qrcodeId`）
     * 不校验签名，且接受 m.iov 用户 token → 直接返回 6 位授权码，
     * 再走 `/oauth/token` 即可拿到 access_token + secret_key（全功能通道）。
     *
     * @return true 表示已拿到 VCS token
     */
    fun authorizeByXiaoanToken(): Boolean {
        val mToken = TokenStore.xiaoanToken
        if (mToken.isEmpty()) return false
        return runCatching {
            val (qid, _) = requestQrCode()
            if (qid.isEmpty()) return@runCatching false
            val resp = ChanganClient.postPlainFormAt(
                INCALL_HOST,
                "/oauth/api/qrcode/authorize",
                mapOf("token" to mToken, "qrcodeId" to qid),
            )
            val code = JSONObject(resp).optString("data")
            if (code.isEmpty()) return@runCatching false
            exchangeToken(code)
        }.getOrDefault(false)
    }

    /** 第 3 步：authorized_code 换 token */
    private fun exchangeToken(authCode: String): Boolean {
        val resp = ChanganClient.postForm(
            "/oauth/token",
            mapOf(
                "client_id" to ChanganSign.CLIENT_ID,
                "client_secret" to ChanganSign.CLIENT_SECRET,
                "grant_type" to "authorization_code",
                "redict_uri" to "",
                "code" to authCode,
            ),
        )
        return saveToken(resp)
    }

    /** 刷新 token（grant_type=refresh_token）。成功返回 true */
    fun refresh(): Boolean {
        if (TokenStore.refreshToken.isEmpty()) return false
        val resp = ChanganClient.postForm(
            "/oauth/token",
            mapOf(
                "client_id" to ChanganSign.CLIENT_ID,
                "client_secret" to ChanganSign.CLIENT_SECRET,
                "grant_type" to "refresh_token",
                "redict_uri" to "",
                "refresh_token" to TokenStore.refreshToken,
            ),
        )
        return runCatching { saveToken(resp) }.getOrDefault(false)
    }

    private fun saveToken(resp: String): Boolean {
        val root = JSONObject(resp)
        if (root.optString("access_token").isEmpty()) return false
        TokenStore.accessToken = root.optString("access_token")
        TokenStore.refreshToken = root.optString("refresh_token")
        TokenStore.secretKey = root.optString("secret_key")
        TokenStore.userId = root.optString("uid")
        val expiresIn = root.optLong("expires_in", 0)
        TokenStore.tokenExpireAt = System.currentTimeMillis() / 1000 + expiresIn
        return TokenStore.hasToken
    }

    /** 车辆列表：POST /open/api/v1.0/car/getList → [{carId, vin, ...}] */
    fun fetchCars(): org.json.JSONArray {
        val resp = ChanganClient.postFormSigned(
            "/open/api/v1.0/car/getList",
            mapOf("accessToken" to TokenStore.accessToken, "pageSize" to 10, "pageIndex" to 1),
        )
        val root = JSONObject(resp)
        if (root.optInt("statusCode", -1) != 0) {
            throw IllegalStateException("车辆列表失败：${root.optString("msg")}")
        }
        return root.optJSONArray("data") ?: org.json.JSONArray()
    }

    /** 车辆详情：GET /openc-apigw/bdc/api/v1/car/get-carInfo-use-carId?carId= */
    fun fetchCarInfo(carId: String): JSONObject {
        val resp = ChanganClient.getVcs(
            "/openc-apigw/bdc/api/v1/car/get-carInfo-use-carId",
            mapOf("carId" to carId),
        )
        val root = JSONObject(resp)
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException("车辆详情失败：${root.optString("msg")}")
        }
        return root.optJSONObject("data") ?: JSONObject()
    }
}
