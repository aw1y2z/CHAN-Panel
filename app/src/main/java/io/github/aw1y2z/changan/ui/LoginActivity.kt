package io.github.aw1y2z.changan.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.data.ChanganAuth
import io.github.aw1y2z.changan.data.DeviceId
import io.github.aw1y2z.changan.data.TokenStore
import io.github.aw1y2z.changan.data.XiaoanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 登录页，两条通道并存：
 *  1. 手机号 + 短信验证码 → 小安控车（微信小程序）通道，能出车况/车图/位置；
 *  2. 引力域 App 扫码 → open.iov VCS 通道，带 secret_key，能下发控车指令。
 */
class LoginActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var polling = false
    private var countdown = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenStore.init(this)
        DeviceId.init(this)

        if (TokenStore.hasToken || TokenStore.hasXiaoan) {
            goDashboard()
            return
        }

        setContentView(R.layout.activity_login)
        findViewById<TextView>(R.id.tvRetry).setOnClickListener { polling = false; startQrLogin() }
        findViewById<TextView>(R.id.tvSendCode).setOnClickListener { sendCode() }
        findViewById<TextView>(R.id.tvPhoneLogin).setOnClickListener { phoneLogin() }
        startQrLogin()
    }

    // ================= 手机号登录（小安控车通道） =================

    private fun phoneMsg(text: String) {
        findViewById<TextView>(R.id.tvPhoneMsg).text = text
    }

    private fun phone(): String = findViewById<EditText>(R.id.etPhone).text.toString().trim()

    private fun code(): String = findViewById<EditText>(R.id.etCode).text.toString().trim()

    private fun sendCode() {
        val mobile = phone()
        if (mobile.length != 11) {
            phoneMsg("请填写 11 位手机号")
            return
        }
        phoneMsg("正在发送验证码…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { XiaoanApi.sendLoginCode(mobile) } }
            if (ok.isSuccess && ok.getOrDefault(false)) {
                phoneMsg("验证码已发送，请查收短信")
                startCountdown()
            } else {
                phoneMsg("发送失败：${ok.exceptionOrNull()?.message ?: "服务端拒绝"}")
            }
        }
    }

    private fun startCountdown() {
        countdown = 60
        scope.launch {
            val tv = findViewById<TextView>(R.id.tvSendCode)
            while (countdown > 0) {
                tv.text = "${countdown}s"
                tv.isEnabled = false
                tv.alpha = 0.5f
                delay(1000)
                countdown--
            }
            tv.text = "获取验证码"
            tv.isEnabled = true
            tv.alpha = 1f
        }
    }

    private fun phoneLogin() {
        val mobile = phone()
        val verify = code()
        if (mobile.length != 11) {
            phoneMsg("请填写 11 位手机号")
            return
        }
        if (verify.length < 4) {
            phoneMsg("请填写短信验证码")
            return
        }
        phoneMsg("正在登录…")
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    XiaoanApi.login(mobile, verify)
                    TokenStore.phone = mobile
                    // 顺带把车辆列表拉一次，缓存 carId / 车名
                    val cars: JSONArray = XiaoanApi.fetchCars()
                    TokenStore.carsCache = cars.toString()
                    if (cars.length() > 0) {
                        val car = cars.getJSONObject(0)
                        TokenStore.xiaoanCarId = car.optString("carId")
                        TokenStore.xiaoanCarName = car.optString("carName")
                        if (TokenStore.carId.isEmpty()) TokenStore.carId = car.optString("carId")
                        if (TokenStore.carVin.isEmpty()) {
                            TokenStore.carVin = car.optString("vin").ifEmpty { car.optString("plateNumber") }
                        }
                    }
                }
            }
            if (r.isSuccess) {
                phoneMsg("登录成功")
                goDashboard()
            } else {
                phoneMsg("登录失败：${r.exceptionOrNull()?.message ?: "未知错误"}")
            }
        }
    }

    // ================= 扫码登录（VCS 通道） =================

    private fun startQrLogin() {
        val msg = findViewById<TextView>(R.id.tvLoginMsg)
        val img = findViewById<ImageView>(R.id.imgQrCode)
        msg.text = "正在获取二维码…"
        scope.launch {
            try {
                val (qrId, png) = withContext(Dispatchers.IO) { ChanganAuth.requestQrCode() }
                img.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
                msg.text = "请用引力域 App 扫码"
                poll(qrId, msg)
            } catch (e: Exception) {
                msg.text = "二维码获取失败：${e.message}"
                findViewById<TextView>(R.id.tvRetry).visibility = TextView.VISIBLE
            }
        }
    }

    private fun poll(qrId: String, msg: TextView) {
        if (polling) return
        polling = true
        scope.launch {
            while (polling) {
                val state = withContext(Dispatchers.IO) {
                    runCatching { ChanganAuth.pollQrStatus(qrId) }.getOrElse { "unauthorized" }
                }
                when (state) {
                    "authorized" -> {
                        polling = false
                        msg.text = "登录成功"
                        goDashboard()
                        return@launch
                    }
                    "token_fail" -> {
                        polling = false
                        msg.text = "换取 token 失败，请重试"
                        findViewById<TextView>(R.id.tvRetry).visibility = TextView.VISIBLE
                        return@launch
                    }
                    "scanned" -> msg.text = "已扫码，请在手机上确认"
                    else -> msg.text = "等待扫码…"
                }
                delay(2000)
            }
        }
    }

    private fun goDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
