package io.github.aw1y2z.changan.data

import android.content.Context
import java.util.UUID

/**
 * 设备指纹：随机 UUID 存 SharedPreferences。
 * 云端按设备维度风控，一台机器固定一个 ID。
 */
object DeviceId {
    private const val PREFS = "device_identity"

    private var full: String? = null
    private var short: String? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        full = prefs.getString("uuid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("uuid", it).apply()
        }
        short = full?.replace("-", "")?.take(16)
    }

    /** 完整 UUID（一般放 x-device-id 类 header） */
    fun fullId(): String = full ?: error("DeviceId not initialized")

    /** 去连字符前 16 位（一般放 deviceSN / imei 类字段） */
    fun shortId(): String = short ?: error("DeviceId not initialized")
}
