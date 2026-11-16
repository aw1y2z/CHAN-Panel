package io.github.aw1y2z.changan.data

import io.github.aw1y2z.changan.BuildConfig

import java.security.MessageDigest
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 长安开放平台签名算法（逆向自长安 inCall 手表端 v1.0.3，见 docs/api-notes.md）。
 *
 * 两套签名并存：
 *
 * A. SignUtil 风格（/oauth、/open/api、/appserver 等表单接口）
 *    签名串 = 所有参数（含 appKey=CLIENTSECRET）按 key 字典序拼 `k=v&`
 *    sign = SHA256Hex(签名串).uppercase()
 *    （appKey 仅参与签名计算，不随请求发送——官方 LoginActivity 严格遵循此做法）
 *
 * B. VCS 风格（/openc-apigw 系列，header 内签名）
 *    header 三件套：X-VCS-User-Access-Token / X-VCS-Timestamp(毫秒) / X-VCS-Nonce
 *    canonical =
 *      METHOD \n path \n [urlencode(query) \n] lower(k):v 逐个换行 \n SHA256Hex(body)
 *      （无 query 时 path 后紧跟空行；body 为空时 SHA256Hex("") ）
 *    stringToSign = "VCS-HMAC-SHA256\n" + timestampMs + "\n" + SHA256Hex(canonical)
 *    Authorization = "VCS-HMAC-SHA256 Signature=" + HMAC-SHA256Hex(secret_key 原文字节, stringToSign)
 *    （secret_key 来自 /oauth/token 响应，形如 "u3M/NE+MAjhAKJ/64tkBMQ=="，直接当 HMAC 密钥用，不解码）
 */
object ChanganSign {

    /**
     * 官方客户端的 OAuth 身份，逆向 APK 即可得到，不是用户凭据。
     *
     * 内置默认值，保证 clone 后无需配置也能登录；若官方调整导致失效，
     * 用 `local.properties` 的 `changan.clientId` / `changan.clientSecret` 覆盖。
     *
     * client_secret 是 `base64(client_id 前 16 位)`，两者同源。
     */
    val CLIENT_ID: String = BuildConfig.CHANGAN_CLIENT_ID.ifEmpty { DEFAULT_CLIENT_ID }
    val CLIENT_SECRET: String = BuildConfig.CHANGAN_CLIENT_SECRET.ifEmpty { DEFAULT_CLIENT_SECRET }

    private const val DEFAULT_CLIENT_ID = "b235afd313004785bfde580c188c47bb"
    private const val DEFAULT_CLIENT_SECRET = "YjIzNWFmZDMxMzAwNDc4NQ=="
    const val NONCE = "WYYL"

    // ---------- 通用 ----------

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun hmacSha256Hex(key: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun urlencodeValue(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    // ---------- A. SignUtil 风格 ----------

    /** params 会被原样发送（sign 除外）；appKey 只参与计算不回写进 params */
    fun signParams(params: Map<String, Any>): String {
        val sorted = TreeMap<String, Any>()
        sorted.putAll(params)
        sorted["appKey"] = CLIENT_SECRET
        val sb = StringBuilder()
        for ((k, v) in sorted) {
            if (k == "sign") continue
            sb.append(k).append("=").append(v).append("&")
        }
        return sha256Hex(sb.toString()).uppercase()
    }

    // ---------- B. VCS 风格 ----------

    /**
     * @param method       "GET" / "POST"
     * @param path         以 / 开头的接口路径（不含域名与 query）
     * @param query        GET 的 query 参数（可为 null / empty）
     * @param body         请求体原文：POST-JSON 传 json 串；POST-Form 传 urlencode 后的表单串；GET 传 null
     * @param headers      参与签名的三个 X-VCS-* header（键值对，不含 Authorization）
     * @param timestampMs  毫秒时间戳（与 X-VCS-Timestamp 一致）
     * @param secretKeyB64 /oauth/token 返回的 secret_key（Base64）
     */
    fun vcsSign(
        method: String,
        path: String,
        query: Map<String, Any>?,
        body: String?,
        headers: Map<String, Any>,
        timestampMs: Long,
        secretKeyB64: String,
    ): String {
        // canonical headers：按 key 排序，key 转小写，`k:v\n`
        val sortedHeaders = TreeMap<String, Any>(String.CASE_INSENSITIVE_ORDER)
        sortedHeaders.putAll(headers)
        val headerStr = sortedHeaders.entries.joinToString("") { "${it.key.lowercase()}:${it.value}\n" }

        val canonical = buildString {
            append(method.uppercase()).append("\n")
            append(path).append("\n")
            if (!query.isNullOrEmpty()) {
                // 官方实现保持插入顺序，仅对 value 做 urlencode，末项无 &
                append(query.entries.joinToString("&") { "${it.key}=${urlencodeValue(it.value.toString())}" })
                append("\n")
            } else {
                append("\n")
            }
            append(headerStr)
            append("\n")
            append(sha256Hex(body ?: ""))
        }
        val hashed = sha256Hex(canonical)
        val stringToSign = "VCS-HMAC-SHA256\n$timestampMs\n$hashed"
        // secret_key 的原文字节直接作 HMAC 密钥，不做 Base64 解码：
        // 解码后再签名，服务端会判签名不通过。
        return "VCS-HMAC-SHA256 Signature=" + hmacSha256Hex(secretKeyB64.toByteArray(), stringToSign)
    }

    /** POST-Form 参与签名的 body 串：k=v& 形式，value urlencode（官方 getUrlencodeStr） */
    fun formBodyForSign(form: Map<String, Any>): String =
        form.entries.joinToString("&") { "${it.key}=${urlencodeValue(it.value.toString())}" }
}
