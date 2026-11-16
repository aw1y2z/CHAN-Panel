package io.github.aw1y2z.changan.data

import io.github.aw1y2z.changan.BuildConfig

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 小安控车（微信小程序）接口，抓包取得。
 *
 * 与 [ChanganClient] 的 open.iov + VCS 签名通道是**两套体系**：
 *  - host：https://m.iov.changan.com.cn
 *  - 认证：token 直接作为表单/查询参数，无 sign、无 timestamp
 *  - 必需请求头：vcs-app-id: inCall
 *  - 登录：短信验证码（手机号），不依赖爱加密的引力域 App
 */
object XiaoanApi {

    const val BASE = "https://m.iov.changan.com.cn"

    /** 小程序的 OAuth 身份，内置默认值保证 clone 可直用，可用 local.properties 覆盖 */
    private const val DEFAULT_CLIENT_ID = "bce34639784c4b159e7ebeda9a5d13bb"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 MicroMessenger/8.0.80 MiniProgramEnv/android"

    private fun builder(): Request.Builder = Request.Builder()
        .header("vcs-app-id", "inCall")
        .header("charset", "utf-8")
        .header("content-type", "application/x-www-form-urlencoded")
        .header("User-Agent", UA)

    private fun get(path: String, query: Map<String, String>): String {
        val url = buildString {
            append(BASE).append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") {
                    "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                })
            }
        }
        return exec(builder().url(url).get())
    }

    private fun post(path: String, form: Map<String, String>): String {
        val body = FormBody.Builder().apply { for ((k, v) in form) add(k, v) }.build()
        return exec(builder().url(BASE + path).post(body))
    }

    private fun exec(rb: Request.Builder): String =
        http.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            Log.i("Changan", "XIAOAN ${resp.code} ${resp.request.url} -> ${text.take(400)}")
            text
        }

    /** 公共参数：token / isNev / type */
    private fun baseParams(): MutableMap<String, String> = mutableMapOf(
        "token" to TokenStore.xiaoanToken,
        "isNev" to "0",
        "type" to "0",
    )

    // ============ 登录 ============

    /** 下发短信验证码：POST /app2/api/user/login/code */
    fun sendLoginCode(mobile: String): Boolean {
        val form = baseParams().apply {
            put("mobile", mobile)
            put("token", "")
        }
        val resp = JSONObject(post("/app2/api/user/login/code", form))
        return resp.optInt("code", -1) == 0
    }

    /**
     * 验证码登录：POST /app2/api/v2/user/login
     * 返回 data.token（后续所有请求都用它）、refreshToken、userId。
     */
    fun login(phone: String, code: String): JSONObject {
        val form = mutableMapOf(
            "phone" to phone,
            "verifyCode" to code,
            "needToken" to "false",
            "isNev" to "0",
            "type" to "0",
        )
        val root = JSONObject(post("/app2/api/v2/user/login", form))
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("msg").ifEmpty { "登录失败" })
        }
        val data = root.optJSONObject("data") ?: throw IllegalStateException("登录返回无数据")
        TokenStore.xiaoanToken = data.optString("token")
        TokenStore.xiaoanRefreshToken = data.optString("refreshToken")
        TokenStore.xiaoanUserId = data.optString("userId")
        if (TokenStore.xiaoanToken.isEmpty()) throw IllegalStateException("未取得 token")
        // 顺手用这个 m.iov token 自助完成 OAuth 授权：成功后同时持有 VCS token，
        // 控车就走 open.iov 全功能通道（不需要扫码、也不需要控车码）。
        runCatching { ChanganAuth.authorizeByXiaoanToken() }
        return data
    }

    /** 刷新小程序 token：GET /cac/api/v1/oauth2/refresh_token */
    fun refreshToken(): Boolean {
        val q = baseParams().apply { put("refresh_token", TokenStore.xiaoanRefreshToken) }
        q["client_id"] = BuildConfig.XIAOAN_CLIENT_ID.ifEmpty { DEFAULT_CLIENT_ID }
        val root = runCatching { JSONObject(get("/cac/api/v1/oauth2/refresh_token", q)) }.getOrNull() ?: return false
        val token = root.optString("access_token")
        if (token.isEmpty()) return false
        TokenStore.xiaoanToken = token
        root.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { TokenStore.xiaoanRefreshToken = it }
        return true
    }

    fun logout() {
        runCatching { post("/api/user/logout", baseParams()) }
        TokenStore.xiaoanToken = ""
        TokenStore.xiaoanRefreshToken = ""
    }

    /**
     * 账号信息：GET /app2/api/user/info →
     * `{mobile:"188****0522", nickName, email, gender, birthday, ...}`
     * mobile 服务端已打码，直接显示即可。
     * 注意只有 GET：POST 会回「请求方法不支持」。
     */
    fun fetchUserInfo(): JSONObject {
        val root = JSONObject(get("/app2/api/user/info", baseParams()))
        return root.optJSONObject("data") ?: JSONObject()
    }

    // ============ 车辆 ============

    /** 车辆列表：GET /app2/api/user/cars，返回 data 数组，含 carId / vin / carName 等 */
    fun fetchCars(): JSONArray {
        val root = JSONObject(get("/app2/api/user/cars", baseParams()))
        return root.optJSONArray("data") ?: JSONArray()
    }

    /**
     * 实时车况：POST /app2/api/car/data（carId + keys=*）。
     *
     * 返回字段和 open.iov 的 get-car-condition-map 基本一一对应：
     * totalOdometer / fuelLeftover / engineWaterTemp / environmentalTemp /
     * passengerDoorLock / diverWindow / trunk / hood / pm25 / vehicleSpeed / terminalTime …
     * 所以手机号通道**也能显示完整车况**，不是只能看车辆档案。
     */
    fun fetchCarStatus(carId: String): JSONObject {
        val form = baseParams().apply {
            put("carId", carId)
            put("keys", "*")
        }
        val root = JSONObject(post("/app2/api/car/data", form))
        return root.optJSONObject("data") ?: JSONObject()
    }

    /** 车辆实时位置：GET /app2/api/car/location */
    fun fetchLocation(carId: String): JSONObject {
        val q = baseParams().apply {
            put("carId", carId)
            put("mapType", "GCJ02")
        }
        val root = JSONObject(get("/app2/api/car/location", q))
        return root.optJSONObject("data") ?: JSONObject()
    }

    // ============ 控车（v3/control/execute + 控车码） ============
    //
    // 链路：
    //   GET  /app2/api/v2/security/key              → data = Base64(X.509 DER) RSA 公钥
    //   POST /app2/api/control/createPinVerifyCode  → data = "<id>::<hash>" 一次性校验串
    //   POST /app2/api/v3/control/execute           carId + s
    //
    // s = Base64( RSA-OAEP(SHA-256, MGF1-SHA256)( JSON ) )，JSON 为
    //   {"pin":"<控车码>","cmd":"01","type":"0","notifyTuType":"0","pinVerifyCode":"<id>::<hash>"}
    //
    // 服务端先校验控车码：码错 → code=21「控车码错误」；字段不对 → 40008「系统服务异常」。
    // 控车码是账号主人自己设的车控密码，因人而异，只能由使用者在本机填写（见 TokenStore）。

    /**
     * 小程序控车的指令编码。
     *
     * 依据（分包 `pages/modules/remoteDemo` 解包后 webpack 字符串池）：
     *   "unlockCar","lockCar","downWindow","raiseWindow","openTrunk","00","01","02","03","04","05"..."09"
     * 5 个动作名与 5 个码严格相邻，即 `{unlockCar:"00", lockCar:"01", downWindow:"02",
     * raiseWindow:"03", openTrunk:"04"}`；`05`~`09` 后面**没有**对应动作名。
     *
     * 05-09 归服务端按钮配置：`/app2/api/v2/car/detail` 的 `cmds` 字段与
     * `json["control/remoteControl1-*"]`（`#lightBtn`/`#airBtn`/`#windowBtn` …）。
     * 手机号通道的 `cmds` 通常为空，能力只看 `blueKeyControl`：
     * `#lockCar/#unlockCar/#downWindow/#raiseWindow/#openTrunk` 为 1，
     * 也就是**手机号通道只开放锁车/解锁/车窗（+后备箱）**，启动、熄火、空调、闪灯、
     * 鸣笛、天窗、寻车都必须走引力域扫码授权（VCS 通道）。
     */
    enum class XiaoanCmd(val code: String, val label: String) {
        UNLOCK("00", "解锁"),
        LOCK("01", "锁车"),
        DOWN_WINDOW("02", "开窗"),
        RAISE_WINDOW("03", "关窗"),
        OPEN_TRUNK("04", "开后备箱"),
    }

    data class ControlOutcome(
        val ok: Boolean,
        val message: String,
        val taskId: String = "",
        val requestId: String = "",
    )

    /** 通用动作 → 小程序指令编码，不支持的动作返回 null（该通道只有 5 个按钮） */
    fun toXiaoanCmd(action: ControlAction): XiaoanCmd? = when (action) {
        ControlAction.UNLOCK -> XiaoanCmd.UNLOCK
        ControlAction.LOCK -> XiaoanCmd.LOCK
        ControlAction.OPEN_WINDOWS -> XiaoanCmd.DOWN_WINDOW
        ControlAction.CLOSE_WINDOWS -> XiaoanCmd.RAISE_WINDOW
        ControlAction.OPEN_TRUNK -> XiaoanCmd.OPEN_TRUNK
        else -> null
    }

    /** 取 RSA 公钥（每次现取，服务端可能轮换） */
    private fun fetchPublicKey(): java.security.PublicKey? {
        val root = runCatching {
            JSONObject(get("/app2/api/v2/security/key", baseParams()))
        }.getOrNull() ?: return null
        val b64 = root.optString("data")
        if (b64.isEmpty()) return null
        return runCatching {
            val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(der))
        }.getOrNull()
    }

    /** 一次性校验串 "<id>::<hash>"，有效期很短，必须在 execute 前现取 */
    private fun createPinVerifyCode(): String {
        val root = runCatching {
            JSONObject(post("/app2/api/control/createPinVerifyCode", baseParams()))
        }.getOrNull() ?: return ""
        return root.optString("data")
    }

    /**
     * RSA-OAEP 加密。
     * 注意 Java 默认的 OAEPWithSHA-256 用 MGF1-SHA1，服务端要的是 **MGF1-SHA256**，
     * 必须显式传 OAEPParameterSpec，否则服务端解不出来。
     */
    private fun encryptOaep(pub: java.security.PublicKey, plain: String): String {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val spec = javax.crypto.spec.OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            java.security.spec.MGF1ParameterSpec.SHA256,
            javax.crypto.spec.PSource.PSpecified.DEFAULT,
        )
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pub, spec)
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /** 下发控车指令（阻塞，需在工作线程调用） */
    fun executeControl(carId: String, pin: String, cmd: XiaoanCmd): ControlOutcome {
        if (pin.isEmpty()) return ControlOutcome(false, "请先填写控车码")
        val pub = fetchPublicKey() ?: return ControlOutcome(false, "获取加密公钥失败，请重试")
        val pv = createPinVerifyCode()
        if (pv.isEmpty()) return ControlOutcome(false, "获取控车校验码失败，请重试")

        val plain = JSONObject()
            .put("pin", pin)
            .put("cmd", cmd.code)
            .put("type", "0")
            .put("notifyTuType", "0")
            .put("pinVerifyCode", pv)
            .toString()
        val s = runCatching { encryptOaep(pub, plain) }
            .getOrElse { return ControlOutcome(false, "指令加密失败：${it.message}") }

        val form = baseParams().apply {
            put("carId", carId)
            put("s", s)
        }
        val root = runCatching { JSONObject(post("/app2/api/v3/control/execute", form)) }
            .getOrNull() ?: return ControlOutcome(false, "网络异常，指令未下发")

        val code = root.optInt("code", -1)
        if (code != 0) {
            val text = when (code) {
                21 -> "控车码错误，请重新填写"
                else -> root.optString("msg").ifEmpty { "指令下发失败($code)" }
            }
            return ControlOutcome(false, text)
        }

        val data = root.opt("data")
        val taskId = when (data) {
            is JSONObject -> data.optString("taskId").ifEmpty { data.optString("id") }
            is String -> data
            else -> ""
        }
        val requestId = (data as? JSONObject)?.optString("requestId").orEmpty()
        return ControlOutcome(true, "「${cmd.label}」指令已下发", taskId, requestId)
    }

    /**
     * 查询控车结果：POST /api/car/getControlInfo。
     * 任务未就绪时服务端返回 msg="指令走丢了，请重试！"，返回 null 表示"还没结果"。
     */
    fun queryControlResult(carId: String, taskId: String, requestId: String): JSONObject? {
        val form = baseParams().apply {
            put("carId", carId)
            if (taskId.isNotEmpty()) put("taskId", taskId)
            if (requestId.isNotEmpty()) put("requestId", requestId)
        }
        val root = runCatching { JSONObject(post("/api/car/getControlInfo", form)) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        return root.optJSONObject("data")
    }

    // ============ 车图 ============
    //
    // 车图不是固定资源，而是服务端按车型下发：
    //   GET /app2/api/resource/list?pubCode=CARPNG&carId=…
    //   data.list[0].block.name        = "CARPNG!C281"   ← C281 就是车辆的 seriesCode（UNI-V）
    //   data.list[0].post.files[0].url = incallcdn.changan.com.cn/…（PNG）
    // 所以缓存必须按车/车型分开，绝不能所有车共用一张图。

    data class CarImage(val url: String, val fileName: String, val seriesCode: String)

    /** 取这台车的官方外观图信息（URL + 车型代号） */
    fun fetchCarImage(carId: String): CarImage? {
        val q = baseParams().apply {
            put("carId", carId)
            put("pubCode", "CARPNG")
        }
        val root = runCatching { JSONObject(get("/app2/api/resource/list", q)) }.getOrNull() ?: return null
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val seriesCode = item.optJSONObject("block")?.optString("name").orEmpty().substringAfter('!', "")
            val files = item.optJSONObject("post")?.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val file = files.optJSONObject(j) ?: continue
                val url = file.optString("url")
                if (url.startsWith("http")) {
                    return CarImage(
                        url = url,
                        fileName = file.optString("name").ifEmpty { "car.png" },
                        seriesCode = seriesCode,
                    )
                }
            }
        }
        return null
    }

    /** 车图缓存文件：按 carId 区分，换车/换账号不会串图 */
    fun carImageCache(dir: java.io.File, carId: String): java.io.File =
        java.io.File(dir, "hero_${carId.takeLast(12)}.png")

    /** 下载车图字节 */
    fun downloadCarImage(url: String): ByteArray? =
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
                .execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        }.getOrNull()

    /**
     * 拉一次官方车图并缓存，返回是否已就绪（缓存命中也算）。
     * 已存在且 [force] 为 false 时直接返回 true。
     */
    fun ensureCarImage(
        cacheFile: java.io.File,
        carId: String,
        force: Boolean = false,
    ): Boolean {
        if (!force && cacheFile.exists() && cacheFile.length() > 1024) return true
        val image = fetchCarImage(carId) ?: return false
        val bytes = downloadCarImage(image.url) ?: return false
        return runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }
}
