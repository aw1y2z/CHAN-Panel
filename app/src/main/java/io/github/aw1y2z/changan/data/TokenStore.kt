package io.github.aw1y2z.changan.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Token / 车辆元数据持久化。
 */
object TokenStore {
    private const val PREFS = "changan_panel"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var accessToken: String
        get() = prefs.getString("access_token", null) ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", null) ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    var userId: String
        get() = prefs.getString("user_id", null) ?: ""
        set(value) = prefs.edit().putString("user_id", value).apply()

    /**
     * `/open/api/v1.0/user/getUserInfo` 返回的 `userId`。
     *
     * 手表 1.1 里 `UID`（OAuth token 的 uid）和 `USER_ID`（这里）是两个不同值：
     * 点熄火消息用前者，安全守护消息用后者。为空时按 userId 兜底。
     */
    var officialUserId: String
        get() = prefs.getString("official_user_id", null) ?: ""
        set(value) = prefs.edit().putString("official_user_id", value).apply()

    /** /oauth/token 返回的 secret_key（Base64），VCS 签名密钥 */
    var secretKey: String
        get() = prefs.getString("secret_key", null) ?: ""
        set(value) = prefs.edit().putString("secret_key", value).apply()

    var tokenExpireAt: Long
        get() = prefs.getLong("token_expire_at", 0)
        set(value) = prefs.edit().putLong("token_expire_at", value).apply()

    // ---- 小安控车（微信小程序通道）会话，与上面的 VCS 会话互不影响 ----

    /** 小程序手机号登录拿到的 token（m.iov.changan.com.cn 用） */
    var xiaoanToken: String
        get() = prefs.getString("xiaoan_token", null) ?: ""
        set(value) = prefs.edit().putString("xiaoan_token", value).apply()

    var xiaoanRefreshToken: String
        get() = prefs.getString("xiaoan_refresh_token", null) ?: ""
        set(value) = prefs.edit().putString("xiaoan_refresh_token", value).apply()

    var xiaoanUserId: String
        get() = prefs.getString("xiaoan_user_id", null) ?: ""
        set(value) = prefs.edit().putString("xiaoan_user_id", value).apply()

    /** 小程序通道下的 carId（与 open.iov 的 carId 是同一个值） */
    var xiaoanCarId: String
        get() = prefs.getString("xiaoan_car_id", null) ?: ""
        set(value) = prefs.edit().putString("xiaoan_car_id", value).apply()

    var xiaoanCarName: String
        get() = prefs.getString("xiaoan_car_name", null) ?: ""
        set(value) = prefs.edit().putString("xiaoan_car_name", value).apply()

    /** 登录手机号（我的页展示；服务端 /app2/api/user/info 也返回已打码的 mobile） */
    var phone: String
        get() = prefs.getString("phone", null) ?: ""
        set(value) = prefs.edit().putString("phone", value).apply()

    /**
     * 外观：0=跟随系统 1=浅色 2=深色。
     *
     * 存原始值而不是 AppCompatDelegate 的常量，避免 UI 层依赖 AppCompat。
     * 应用时机在 [io.github.aw1y2z.changan.App.onCreate]，进程启动就定好，不会闪白。
     */
    var nightMode: Int
        get() = prefs.getInt("night_mode", 0)
        set(value) = prefs.edit().putInt("night_mode", value).apply()

    /** 车辆列表 JSON 缓存，我的页/切车离线也能用 */
    var carsCache: String
        get() = prefs.getString("cars_cache", null) ?: ""
        set(value) = prefs.edit().putString("cars_cache", value).apply()

    /** 车辆列表缓存时间（毫秒）：我的页据此决定要不要重新拉网络 */
    var carsCacheAt: Long
        get() = prefs.getLong("cars_cache_at", 0L)
        set(value) = prefs.edit().putLong("cars_cache_at", value).apply()

    /**
     * 车辆显示别名。服务端**没有**改名接口（小程序全部接口里没有 carName 更新），
     * 所以改名只存本机，按 carId 分开。
     */
    fun aliasOf(carId: String): String =
        if (carId.isEmpty()) "" else prefs.getString("alias_$carId", null) ?: ""

    fun setAlias(carId: String, alias: String) {
        if (carId.isEmpty()) return
        prefs.edit().putString("alias_$carId", alias).apply()
    }

    /** 当前车显示名：本机别名 > 车名 > 车牌/VIN */
    fun displayNameOf(carId: String, fallback: String = ""): String =
        aliasOf(carId).ifEmpty { fallback }.ifEmpty { xiaoanCarName }.ifEmpty { carVin }.ifEmpty { "长安汽车" }

    /**
     * 控车码（车控 PIN）。
     *
     * 是账号主人自己在「小安控车」里设置的车控密码，**不同用户不一样**，
     * 所以绝不能内置，只能由使用者在 App 里首次控车时填写、只存在本机。
     */
    var xiaoanControlPin: String
        get() = prefs.getString("xiaoan_control_pin", null) ?: ""
        set(value) = prefs.edit().putString("xiaoan_control_pin", value).apply()

    // ---- 控车码管理：三项本地开关 ----

    /** 控车码开关：关掉后控车不再要求输入控车码 */
    var controlPinEnabled: Boolean
        get() = prefs.getBoolean("control_pin_enabled", true)
        set(value) = prefs.edit().putBoolean("control_pin_enabled", value).apply()

    /** 操作二次确认：下发指令前弹窗确认（默认关闭） */
    var controlPinConfirm: Boolean
        get() = prefs.getBoolean("control_pin_confirm", false)
        set(value) = prefs.edit().putBoolean("control_pin_confirm", value).apply()

    val hasXiaoan: Boolean get() = xiaoanToken.isNotEmpty()

    /** 当前车 carId（控车/车况都用它，不是 VIN） */
    var carId: String
        get() = prefs.getString("car_id", null) ?: ""
        set(value) = prefs.edit().putString("car_id", value).apply()

    var carVin: String
        get() = prefs.getString("car_vin", null) ?: ""
        set(value) = prefs.edit().putString("car_vin", value).apply()

    /** 车辆详情缓存 */
    var vehicleMeta: JSONObject?
        get() = prefs.getString("vehicle_meta", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(value) = prefs.edit().putString("vehicle_meta", value?.toString()).apply()

    /** 小程序 token 过期时间（秒级时间戳），用于进入应用时判断是否需要提前刷新 */
    var xiaoanTokenExpireAt: Long
        get() = prefs.getLong("xiaoan_token_expire_at", 0L)
        set(value) = prefs.edit().putLong("xiaoan_token_expire_at", value).apply()

    /** MQTT clientId 里的设备标识，首次生成后固定 */
    var mqttDeviceUuid: String
        get() = prefs.getString("mqtt_device_uuid", "") ?: ""
        set(value) = prefs.edit().putString("mqtt_device_uuid", value).apply()

    // ---- 控车协议：auto / open / v3 / v5 ----

    /** 控车协议选择，默认自动（依次尝试直到成功） */
    var controlProtocol: String
        get() = prefs.getString("control_protocol", "auto") ?: "auto"
        set(value) = prefs.edit().putString("control_protocol", value).apply()

    /** 自动模式下最近一次成功的协议，下次优先尝试 */
    var lastOkProtocol: String
        get() = prefs.getString("control_protocol_last_ok", "") ?: ""
        set(value) = prefs.edit().putString("control_protocol_last_ok", value).apply()

    val hasToken: Boolean get() = accessToken.isNotEmpty() && secretKey.isNotEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
