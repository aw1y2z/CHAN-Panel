package io.github.aw1y2z.changan.data

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 长安开放平台 HTTP 客户端（base：https://open.iov.changan.com.cn）。
 * 协议逆向自长安 inCall 手表端，两种签名见 [ChanganSign]。
 */
object ChanganClient {

    const val BASE_URL = "https://open.iov.changan.com.cn"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun deviceId(): String = runCatching { DeviceId.fullId() }.getOrDefault("")

    // ============ A. SignUtil 风格表单接口（/oauth、/open/api） ============

    /** POST 表单，参数自动附加 timestamp(秒)/nonce/sign；返回响应体字符串 */
    fun postFormSigned(path: String, params: Map<String, Any>): String {
        val p = HashMap<String, Any>(params)
        p["timestamp"] = System.currentTimeMillis() / 1000
        p["nonce"] = ChanganSign.NONCE
        val sign = ChanganSign.signParams(p) // appKey 只参与计算
        val body = FormBody.Builder().apply {
            for ((k, v) in p) add(k, v.toString())
            add("sign", sign)
        }.build()
        return exec(Request.Builder().url(BASE_URL + path).post(body))
    }

    /** POST 纯表单（如 /oauth/token），无签名 */
    fun postForm(path: String, params: Map<String, Any>): String {
        val body = FormBody.Builder().apply {
            for ((k, v) in params) add(k, v.toString())
        }.build()
        return exec(Request.Builder().url(BASE_URL + path).post(body))
    }

    /**
     * 其他网关的裸表单 POST，不带签名。
     * 仅用于 `incallapi.changan.com.cn/oauth/api/qrcode/authorize`：该接口不校验 sign。
     */
    fun postPlainFormAt(host: String, path: String, params: Map<String, String>): String {
        val body = FormBody.Builder().apply {
            for ((k, v) in params) add(k, v)
        }.build()
        return exec(Request.Builder().url(host + path).post(body))
    }

    /** GET，query 自动附加 timestamp(秒)/nonce/sign，返回响应体字符串 */
    fun getSigned(path: String, query: Map<String, Any>): String {
        val q = HashMap<String, Any>(query)
        q["timestamp"] = System.currentTimeMillis() / 1000
        q["nonce"] = ChanganSign.NONCE
        val sign = ChanganSign.signParams(q)
        val url = buildString {
            append(BASE_URL).append(path).append("?")
            append(q.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value.toString(), "UTF-8")}" })
            append("&sign=").append(java.net.URLEncoder.encode(sign, "UTF-8"))
        }
        return exec(Request.Builder().url(url).get())
    }

    // ============ B. VCS 签名接口（/openc-apigw/**） ============

    private fun vcsHeaders(): MutableMap<String, Any> {
        val ts = System.currentTimeMillis()
        return mutableMapOf(
            "X-VCS-User-Access-Token" to TokenStore.accessToken,
            "X-VCS-Timestamp" to ts,
            "X-VCS-Nonce" to ChanganSign.NONCE,
        )
    }

    /** POST JSON（控车指令）。body 顶层字段官方顺序：mapArgsJson, senderType, cmd, carId */
    fun postJsonVcs(path: String, json: String): String = vcsRetry {
        val headers = vcsHeaders()
        val auth = ChanganSign.vcsSign("POST", path, null, json, headers, headers["X-VCS-Timestamp"] as Long, TokenStore.secretKey)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        exec(Request.Builder().url(BASE_URL + path).post(body).apply {
            for ((k, v) in headers) header(k, v.toString())
            header("Authorization", auth)
        })
    }

    /** POST Form（车况查询），签名 body 用 urlencode 表单串 */
    fun postFormVcs(path: String, form: Map<String, Any>): String = vcsRetry {
        val headers = vcsHeaders()
        val bodyStr = ChanganSign.formBodyForSign(form)
        val auth = ChanganSign.vcsSign("POST", path, null, bodyStr, headers, headers["X-VCS-Timestamp"] as Long, TokenStore.secretKey)
        val body = FormBody.Builder().apply { for ((k, v) in form) add(k, v.toString()) }.build()
        exec(Request.Builder().url(BASE_URL + path).post(body).apply {
            for ((k, v) in headers) header(k, v.toString())
            header("Authorization", auth)
        })
    }

    /**
     * GET + query，额外携带 `X-VCS-Token`（与 token 同值）。
     *
     * 手表 1.1 的 `getCarListByBrandCode` 同时带 `X-VCS-User-Access-Token` 和 `X-VCS-Token`，
     * 且两个头都参与签名；车况等接口只带前者。多出的头会改变签名，所以单独一个方法。
     */
    fun getVcsWithToken(path: String, query: Map<String, Any>): String = vcsRetry {
        val headers = vcsHeaders()
        headers["X-VCS-Token"] = TokenStore.accessToken
        val auth = ChanganSign.vcsSign("GET", path, query, null, headers, headers["X-VCS-Timestamp"] as Long, TokenStore.secretKey)
        val url = buildString {
            append(BASE_URL).append(path).append("?")
            append(query.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value.toString(), "UTF-8")}" })
        }
        exec(Request.Builder().url(url).get().apply {
            for ((k, v) in headers) header(k, v.toString())
            header("Authorization", auth)
        })
    }

    /** GET + query（控车结果轮询），query 参与签名 */
    fun getVcs(path: String, query: Map<String, Any>): String = vcsRetry {
        val headers = vcsHeaders()
        val auth = ChanganSign.vcsSign("GET", path, query, null, headers, headers["X-VCS-Timestamp"] as Long, TokenStore.secretKey)
        val url = buildString {
            append(BASE_URL).append(path).append("?")
            append(query.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value.toString(), "UTF-8")}" })
        }
        exec(Request.Builder().url(url).get().apply {
            for ((k, v) in headers) header(k, v.toString())
            header("Authorization", auth)
        })
    }

    private fun exec(rb: Request.Builder): String =
        http.newCall(rb.header("User-Agent", "okhttp/4.12.0").build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            android.util.Log.i("Changan", "HTTP ${resp.code} ${resp.request.url} -> ${bodyStr.take(600)}")
            bodyStr
        }

    /** 服务端 token 失效码（手表 1.1 的 TokenRefreshInterceptor 用的就是 46000） */
    private fun isTokenExpired(body: String): Boolean =
        body.contains("\"code\":46000") || body.contains("\"statusCode\":46000")

    /**
     * VCS 请求的自动续期：命中 46000 就刷新 token 再重放一次。
     *
     * block 每次执行都会重新读 [TokenStore]，所以刷新后的新 token / secretKey 会用上。
     */
    private inline fun vcsRetry(crossinline block: () -> String): String {
        val first = block()
        if (!isTokenExpired(first) || !TokenStore.hasToken) return first
        if (!ChanganAuth.refresh()) return first
        return block()
    }

    /**
     * 登录二维码请求特殊：响应体是 PNG 图片，qrcode_id 在响应头里，参数需带 sign。
     * extra 里的字段在**算完 sign 之后**才追加（官方 appCode=usercenter 不参与签名，少了它服务端返回
     * 「生成二维码失败！」）。
     */
    fun postFormForBytes(
        path: String,
        params: Map<String, Any>,
        extra: Map<String, String> = emptyMap(),
    ): Pair<String?, ByteArray?> {
        val p = HashMap<String, Any>(params)
        p["timestamp"] = System.currentTimeMillis() / 1000
        p["nonce"] = ChanganSign.NONCE
        val sign = ChanganSign.signParams(p)
        val body = FormBody.Builder().apply {
            for ((k, v) in p) add(k, v.toString())
            for ((k, v) in extra) add(k, v)
            add("sign", sign)
        }.build()
        http.newCall(Request.Builder().url(BASE_URL + path).post(body).build()).execute().use { resp ->
            val bytes = resp.body?.bytes()
            android.util.Log.i("Changan", "QR HTTP ${resp.code} qrcode_id=${resp.header("qrcode_id")} ctype=${resp.header("Content-Type")} len=${bytes?.size}")
            return resp.header("qrcode_id") to bytes
        }
    }
}
