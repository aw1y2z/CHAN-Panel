package io.github.aw1y2z.changan.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.aw1y2z.changan.App
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.data.ChanganAuth
import io.github.aw1y2z.changan.data.InfoCenter
import io.github.aw1y2z.changan.data.TokenStore
import io.github.aw1y2z.changan.data.XiaoanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「我的」页：账号、多车切换与改名、关于、退出登录。
 *
 * 「我的」页：作为 [DashboardActivity] 的第三个视图（`view_mine.xml`），
 * 由底部导航直接显隐，三个 tab 切换不跳 Activity。
 *
 * 头像 + 打码手机号 + 设置列表 + 退出登录；车辆来自 `GET /app2/api/user/cars`。
 *
 * 改名只能改本机显示名——官方没有 carName 更新接口。
 */
class MinePage(
    private val activity: AppCompatActivity,
    private val root: View,
    /** 切车后通知宿主：车名、车图、车况都得跟着换 */
    private val onCarSwitched: () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val carList: LinearLayout = root.findViewById(R.id.carList)
    private val tvCarCount: TextView = root.findViewById(R.id.tvCarCount)
    private val tvAccount: TextView = root.findViewById(R.id.tvAccount)
    private val tvAccountSub: TextView = root.findViewById(R.id.tvAccountSub)
    private val tvThemeValue: TextView = root.findViewById(R.id.tvThemeValue)
    private val tvInfoUnread: TextView = root.findViewById(R.id.tvInfoUnread)

    // ---- 头像三态：相册图 > 本机生成的彩色数字 > 占位图标 ----

    private val avatarBox: View = root.findViewById(R.id.avatarBox)
    private val ivAvatarPhoto: ImageView = root.findViewById(R.id.ivAvatarPhoto)
    private val ivAvatarPlaceholder: ImageView = root.findViewById(R.id.ivAvatarPlaceholder)
    private val tvAvatarText: TextView = root.findViewById(R.id.tvAvatarText)

    /** 相册选的图，只存本机，不涉及任何官方接口 */
    private val localAvatarFile: File by lazy { File(activity.filesDir, "avatar_local.img") }

    /** 上次渲染头像用的签名（文件时间戳 + 号码），没变就不重复解码 */
    private var avatarSig = ""

    private val pickAvatar = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) savePickedAvatar(uri) }

    private var cars: List<JSONObject> = emptyList()

    /** 上次渲染的列表签名：内容没变就不重建视图，避免进页面时闪一下 */
    private var renderedSig = ""

    /** 车辆列表只在本页首次打开时拉一次，之后进页面直接沿用 */
    private var loaded = false

    /** 内存里的手机号：扫码登录时本地没存，取到一次就记住 */
    private var cachedPhone = ""

    private var accountFetching = false

    init {
        tvCarCount.setOnClickListener {
            Toast.makeText(activity, "正在刷新车辆…", Toast.LENGTH_SHORT).show()
            loadCars(force = true)
        }
        root.findViewById<View>(R.id.rowTheme).setOnClickListener { pickTheme() }
        root.findViewById<View>(R.id.rowAbout).setOnClickListener { showAbout() }
        root.findViewById<View>(R.id.btnLogout).setOnClickListener { logout() }
        root.findViewById<View>(R.id.rowInfoCenter).setOnClickListener {
            activity.startActivity(Intent(activity, InfoCenterActivity::class.java))
        }
        avatarBox.setOnClickListener { pickAvatarSource() }

        root.findViewById<TextView>(R.id.tvVersion).text = "v" + runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull().orEmpty().ifEmpty { "1.0" }

        renderTheme()
        renderAvatar()
    }

    /** 信息中心未读数：角标只是提示，读不到就当 0 */
    fun refreshInfoBadge() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { InfoCenter.unreadCount(activity) }
            if (n <= 0) {
                tvInfoUnread.visibility = View.GONE
            } else {
                tvInfoUnread.visibility = View.VISIBLE
                tvInfoUnread.text = if (n > 99) "99+" else n.toString()
            }
        }
    }

    /** 每次切到「我的」都会调：首次完整渲染，之后只刷账号行 */
    fun onShown() {
        renderAccount()
        renderAvatar()
        refreshInfoBadge()
        if (loaded) return
        loaded = true
        renderCarsFromCache()
        ensureCurrentCar()
        loadCars(force = false)
    }

    fun destroy() {
        scope.cancel()
    }

    // ================= 账号 =================

    private fun renderAccount() {
        val phone = TokenStore.phone.ifEmpty { cachedPhone }
        val masked = maskPhone(phone)
        // 扫码通道登录时本地没手机号，但已是登录态，不能写「未登录」
        tvAccount.text = when {
            masked.isNotEmpty() -> masked
            TokenStore.hasToken || TokenStore.hasXiaoan -> "已登录"
            else -> "未登录"
        }
        tvAccountSub.text = when {
            TokenStore.hasToken && TokenStore.hasXiaoan -> "扫码授权 + 手机号通道"
            TokenStore.hasToken -> "扫码授权通道（全功能）"
            else -> "手机号通道"
        }
        // 拿到过的号码要落盘：否则每次进页面都重查一遍，先显示占位再跳成手机号
        if (phone.isNotEmpty() || accountFetching) return
        if (!TokenStore.hasXiaoan) return
        accountFetching = true
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { XiaoanApi.fetchUserInfo() }.getOrNull()
            }
            accountFetching = false
            val mobile = info?.optString("mobile").orEmpty().trim()
            if (mobile.isEmpty()) return@launch
            cachedPhone = mobile
            TokenStore.phone = mobile
            tvAccount.text = maskPhone(mobile)
            renderAvatar()
        }
    }

    private fun maskPhone(phone: String): String =
        if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone

    // ================= 头像（纯本地） =================

    /**
     * 头像三态，全部来自本机，不访问任何官方接口：
     * 相册自选图 > 按号码生成的彩色数字 > 未登录时的占位图标。
     *
     * 断网、换账号、服务端不返回头像，都不影响「我的」页的观感。
     */
    private fun renderAvatar() {
        val phone = TokenStore.phone.ifEmpty { cachedPhone }
        val sig = localAvatarFile.lastModified().toString() + '|' + phone
        if (sig == avatarSig) return
        avatarSig = sig

        val bitmap = if (localAvatarFile.exists() && localAvatarFile.length() > 0) {
            runCatching { BitmapFactory.decodeFile(localAvatarFile.absolutePath) }.getOrNull()
        } else {
            null
        }
        if (bitmap != null) {
            ivAvatarPhoto.setImageBitmap(circleCrop(bitmap))
            ivAvatarPhoto.visibility = View.VISIBLE
            ivAvatarPlaceholder.visibility = View.GONE
            tvAvatarText.visibility = View.GONE
            avatarBox.setBackgroundResource(R.drawable.bg_avatar_circle)
            return
        }

        ivAvatarPhoto.setImageDrawable(null)
        ivAvatarPhoto.visibility = View.GONE
        val digits = phone.filter { it.isDigit() }.takeLast(4)
        if (digits.length == 4) {
            tvAvatarText.text = digits
            tvAvatarText.visibility = View.VISIBLE
            ivAvatarPlaceholder.visibility = View.GONE
            avatarBox.background = colorAvatar(digits)
        } else {
            tvAvatarText.visibility = View.GONE
            ivAvatarPlaceholder.visibility = View.VISIBLE
            avatarBox.setBackgroundResource(R.drawable.bg_avatar_circle)
        }
    }

    /** 按后 4 位挑一个固定色，同一个号码每次进来颜色都一样 */
    private fun colorAvatar(key: String): GradientDrawable {
        val palette = intArrayOf(
            0xFF4C7DF0.toInt(), 0xFFE0643C.toInt(), 0xFF2FA37C.toInt(),
            0xFF8A5BD6.toInt(), 0xFFC9971B.toInt(), 0xFF3B8FB0.toInt(),
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette[(key.hashCode() and 0x7FFFFFFF) % palette.size])
        }
    }

    /** 相册给的方图裁成圆形，正好铺满 76dp 的圆形容器 */
    private fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        if (size <= 0) return src
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = size.toFloat() / minOf(src.width, src.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        val r = size / 2f
        Canvas(out).drawCircle(r, r, r, paint)
        return out
    }

    private fun pickAvatarSource() {
        val hasPhoto = localAvatarFile.exists() && localAvatarFile.length() > 0
        val items = if (hasPhoto) arrayOf("从相册选择", "恢复默认头像") else arrayOf("从相册选择")
        MaterialAlertDialogBuilder(activity)
            .setTitle("头像")
            .setItems(items) { _, which ->
                if (which == 0) {
                    pickAvatar.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } else {
                    clearAvatar()
                }
            }
            .show()
    }

    private fun savePickedAvatar(uri: Uri) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { ins ->
                        localAvatarFile.outputStream().use { ins.copyTo(it) }
                    } != null
                }.getOrDefault(false)
            }
            if (!ok) {
                Toast.makeText(activity, "读取图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            avatarSig = ""
            renderAvatar()
        }
    }

    private fun clearAvatar() {
        localAvatarFile.delete()
        avatarSig = ""
        renderAvatar()
    }

    // ================= 车辆 =================

    private fun currentCarId(): String = TokenStore.xiaoanCarId.ifEmpty { TokenStore.carId }

    /** 先用本地缓存秒开，再拉网络 */
    private fun renderCarsFromCache() {
        val raw = TokenStore.carsCache
        if (raw.isEmpty()) return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        cars = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        renderCars()
    }

    private fun loadCars(force: Boolean = false) {
        val fresh = System.currentTimeMillis() - TokenStore.carsCacheAt < CACHE_TTL_MS
        // 缓存还新鲜就不打网络：进「我的」时列表直接沿用上次结果，不会重建成空页
        if (!force && fresh && cars.isNotEmpty()) return
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        TokenStore.hasXiaoan -> XiaoanApi.fetchCars()
                        TokenStore.hasToken -> ChanganAuth.fetchCars()
                        else -> JSONArray()
                    }
                }.getOrNull()
            }
            if (list == null || list.length() == 0) return@launch
            TokenStore.carsCache = list.toString()
            TokenStore.carsCacheAt = System.currentTimeMillis()
            cars = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            ensureCurrentCar()
            renderCars()
        }
    }

    /** 当前车不在列表里（换账号 / 车辆被解绑）就落到第一辆 */
    private fun ensureCurrentCar() {
        if (cars.isEmpty()) return
        val ids = cars.map { it.optString("carId") }
        if (currentCarId().isEmpty() || currentCarId() !in ids) {
            bindCar(cars.first(), silent = true)
            onCarSwitched()
        }
    }

    private fun renderCars() {
        val current = currentCarId()
        val sig = buildString {
            cars.forEach {
                val id = it.optString("carId")
                append(id).append('#')
                append(TokenStore.aliasOf(id)).append('#')
                append(it.optString("carName")).append('|')
            }
            append('@').append(current)
        }
        if (sig == renderedSig) return
        renderedSig = sig

        carList.removeAllViews()
        tvCarCount.text = if (cars.isEmpty()) "--" else "${cars.size} 辆"

        if (cars.isEmpty()) {
            carList.addView(buildEmptyRow())
            return
        }

        val gap = (8 * activity.resources.displayMetrics.density).toInt()
        cars.forEachIndexed { index, car ->
            val carId = car.optString("carId")
            val row = activity.layoutInflater.inflate(R.layout.item_car_row, carList, false)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (index == 0) 0 else gap }

            val isCurrent = carId.isNotEmpty() && carId == current
            val name = row.findViewById<TextView>(R.id.tvCarRowName)
            val sub = row.findViewById<TextView>(R.id.tvCarRowSub)
            val tag = row.findViewById<TextView>(R.id.tvCarRowCurrent)
            val icon = row.findViewById<ImageView>(R.id.ivCarRowIcon)

            name.text = TokenStore.aliasOf(carId).ifEmpty { carNameOf(car) }
            tag.visibility = if (isCurrent) View.VISIBLE else View.GONE
            icon.setColorFilter(activity.getColor(if (isCurrent) R.color.brand else R.color.txt_tertiary))
            sub.text = carSubOf(car).ifEmpty { "carId ${carId.takeLast(6)}" }

            row.setOnClickListener { switchTo(car) }
            row.findViewById<View>(R.id.btnCarEdit).setOnClickListener { rename(car) }
            carList.addView(row)
        }
    }

    private fun buildEmptyRow(): View {
        val density = activity.resources.displayMetrics.density
        val tv = TextView(activity).apply {
            text = if (TokenStore.hasToken) "未取到车辆列表，点右上角数量重试" else "该账号下没有已授权车辆"
            textSize = 12f
            setTextColor(activity.getColor(R.color.txt_tertiary))
            gravity = Gravity.CENTER
            setPadding(0, (26 * density).toInt(), 0, (26 * density).toInt())
        }
        tv.background = activity.getDrawable(R.drawable.bg_card)
        return tv
    }

    private fun carNameOf(car: JSONObject): String =
        car.optString("carName")
            .ifEmpty { car.optString("seriesName") }
            .ifEmpty { car.optString("plateNumber") }
            .ifEmpty { car.optString("plateNo") }
            .ifEmpty { car.optString("vin") }

    private fun carSubOf(car: JSONObject): String = buildString {
        val plate = car.optString("plateNumber").ifEmpty { car.optString("plateNo") }
        if (plate.isNotEmpty()) append(plate)
        val vin = car.optString("vin")
        if (vin.length >= 6) {
            if (isNotEmpty()) append(" · ")
            append("VIN ").append(vin.takeLast(6))
        }
        val series = car.optString("seriesName").ifEmpty { car.optString("carName") }
        if (series.isNotEmpty() && series != carNameOf(car)) {
            if (isNotEmpty()) append(" · ")
            append(series)
        }
    }

    /** 切换当前车：两条通道的 carId 是同一个值，一起改 */
    private fun switchTo(car: JSONObject) {
        val carId = car.optString("carId")
        if (carId.isEmpty() || carId == currentCarId()) return
        bindCar(car, silent = false)
        renderCars()
        onCarSwitched()
    }

    private fun bindCar(car: JSONObject, silent: Boolean) {
        val carId = car.optString("carId")
        if (carId.isEmpty()) return
        TokenStore.xiaoanCarId = carId
        TokenStore.carId = carId
        val vin = car.optString("vin")
        if (vin.isNotEmpty()) TokenStore.carVin = vin
        val name = car.optString("carName").ifEmpty { car.optString("seriesName") }
        if (name.isNotEmpty()) TokenStore.xiaoanCarName = name
        if (!silent) {
            Toast.makeText(
                activity,
                "已切换到「${TokenStore.aliasOf(carId).ifEmpty { carNameOf(car) }}」",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun rename(car: JSONObject) {
        val carId = car.optString("carId")
        if (carId.isEmpty()) return
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(activity).apply {
            setSingleLine()
            setText(TokenStore.aliasOf(carId).ifEmpty { carNameOf(car) })
            setSelection(text.length)
            hint = "车辆名称"
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (pad / 2), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("修改车辆名称")
            .setMessage("只改本机显示名：官方接口没有车辆改名能力，不会同步到车机或小程序。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                TokenStore.setAlias(carId, input.text.toString().trim())
                renderCars()
                onCarSwitched()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                TokenStore.setAlias(carId, "")
                renderCars()
                onCarSwitched()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 设置 =================

    private fun renderTheme() {
        tvThemeValue.text = themeLabel(TokenStore.nightMode)
    }

    private fun themeLabel(mode: Int): String = when (mode) {
        1 -> "浅色"
        2 -> "深色"
        else -> "跟随系统"
    }

    /**
     * 三选一：跟随系统 / 浅色 / 深色。
     *
     * 切换后 [AppCompatDelegate.setDefaultNightMode] 会让当前 Activity 重建，
     * 停在哪个 tab 由 DashboardActivity 的 savedInstanceState 保住。
     */
    private fun pickTheme() {
        val modes = intArrayOf(0, 1, 2)
        val labels = arrayOf("跟随系统", "浅色", "深色")
        val checked = modes.indexOf(TokenStore.nightMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle("深色模式")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val mode = modes[which]
                if (mode == TokenStore.nightMode) return@setSingleChoiceItems
                TokenStore.nightMode = mode
                renderTheme()
                AppCompatDelegate.setDefaultNightMode(App.delegateModeOf(mode))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAbout() {
        val version = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        MaterialAlertDialogBuilder(activity)
            .setTitle("关于")
            .setMessage(
                """
                CHAN-Panel · 第三方开源客户端 v$version

                协议来自长安 inCall 手表端与「小安控车」小程序的逆向，
                与长安汽车官方无关，仅供学习研究，请勿用于商业用途。

                所有 token、控车码、车辆别名只保存在本机。
                """.trimIndent(),
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun logout() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("退出登录")
            .setMessage("退出后需要重新用手机号或扫码授权登录，本机保存的控车码、车辆别名也会一并清除。")
            .setPositiveButton("退出") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { XiaoanApi.logout() } }
                    localAvatarFile.delete()
                    avatarSig = ""
                    TokenStore.clear()
                    activity.startActivity(
                        Intent(activity, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                    activity.finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private companion object {
        /** 车辆列表缓存有效期：5 分钟内进「我的」不再打网络 */
        const val CACHE_TTL_MS = 5 * 60_000L
    }
}
