package io.github.aw1y2z.changan.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.BuildConfig
import io.github.aw1y2z.changan.data.ChanganAuth
import io.github.aw1y2z.changan.data.ControlAction
import io.github.aw1y2z.changan.data.ControlActions
import io.github.aw1y2z.changan.data.DeviceId
import io.github.aw1y2z.changan.data.InfoCenter
import io.github.aw1y2z.changan.data.MqttCarStatus
import io.github.aw1y2z.changan.data.NotifyCenter
import io.github.aw1y2z.changan.data.SafetyCheck
import io.github.aw1y2z.changan.data.TokenStore
import io.github.aw1y2z.changan.data.XiaoanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** savedInstanceState 里记当前 tab：切深色模式重建 Activity 后能停在同一页 */
private const val KEY_TAB = "tab"

/**
 * 主界面：三个页面同层显隐，无 Activity 跳转。
 * 顶部车名/在线状态、仪表盘（车况卡片）、控车（动作块 + 空调条），底部浮动导航。
 */
class DashboardActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var tvCarName: TextView
    private lateinit var tvOnline: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvFuel: TextView

    /** 控车页顶部状态胶囊：车锁 / 发动机 / 车窗 */
    private lateinit var pillLock: TextView
    private lateinit var pillEngine: TextView
    private lateinit var pillWindow: TextView



    /** 2×8 车况网格 */
    private lateinit var tvStLock: TextView
    private lateinit var tvStDoor: TextView
    private lateinit var tvStWindow: TextView
    private lateinit var tvStSunroof: TextView
    private lateinit var tvStTrunk: TextView
    private lateinit var tvStHood: TextView
    private lateinit var tvStAc: TextView
    private lateinit var tvStTyreF: TextView
    private lateinit var tvStTyreR: TextView
    private lateinit var tvStBattery: TextView
    private lateinit var tvStPm25: TextView
    private lateinit var tvStInTemp: TextView
    private lateinit var tvStOdometer: TextView
    private lateinit var tvStFuelEco: TextView
    private lateinit var tvStKey: TextView
    private lateinit var tvStWater: TextView

    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var contentScroll: android.widget.ScrollView
    private lateinit var tvAddress: TextView
    private lateinit var tvLocMeta: TextView
    private lateinit var pageStatus: View
    private lateinit var pageControl: View
    private lateinit var pageMine: View
    private lateinit var minePage: MinePage

    /** 0=仪表盘 1=控车 2=我的；默认停在控车页 */
    private var currentTab = 1

    private var acTemp = 22

    /** 用户手动调过温度后，刷新车况不再回写，避免刚调好又被覆盖 */
    private var acTempTouched = false

    /** 当前绑定的车辆，用来检测「我的」页切车 */
    private var boundCarId = ""

    /** 防并发刷新（onCreate + onResume + 下拉手势可能叠在一起） */
    private var refreshing = false

    /** 最近一次定位：lat / lng / 是否有有效坐标 */
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var hasLocation = false

    /**
     * 控车页功能块的当前顺序，用户长按可拖拽调整，存在本机。
     * 空调是独立卡片（要带温度），不参与这个网格。
     */
    private val controlOrder = ArrayList<ControlTile>()
    private lateinit var rvControl: androidx.recyclerview.widget.RecyclerView
    private lateinit var tileAdapter: ControlTileAdapter
    private var editTipShown = false

    /**
     * 会让车真的「动起来」或开盖的动作，点击后二次确认；
     * 关类 / 灯光 / 喇叭这类误触也无害，直接下发。
     */
    private val needConfirm = setOf(
        ControlAction.UNLOCK,
        ControlAction.REMOTE_START,
        ControlAction.OPEN_WINDOWS,
        ControlAction.WINDOW_SLIT,
        ControlAction.OPEN_SUNROOF,
        ControlAction.OPEN_TRUNK,
        ControlAction.OPEN_AIR,
    )

    /** Android 13+ 要运行时通知权限；拒绝也不影响应用内消息中心，只是不弹通知 */
    private fun requestNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenStore.init(this)
        DeviceId.init(this)

        if (!TokenStore.hasToken && !TokenStore.hasXiaoan) {
            finish()
            return
        }

        setContentView(R.layout.activity_dashboard)
        requestNotifyPermission()
        bindViews()
        setupStatLabels()
        minePage = MinePage(this, pageMine) { onCarSwitched() }
        buildListeners()
        applyChannelCapability()
        // 切换深色模式会重建 Activity，先恢复到重建前的 tab；默认进控车页
        switchTab(savedInstanceState?.getInt(KEY_TAB) ?: intent.getIntExtra("tab", 1))
        boundCarId = currentCarId()
        loadCarImage()
        refreshAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        switchTab(intent.getIntExtra("tab", 1))
    }

    /** 兜底：从地图/拨号等外部页面回来时，若车被换过就整页重来 */
    override fun onResume() {
        super.onResume()
        val current = currentCarId()
        val changed = boundCarId.isNotEmpty() && current.isNotEmpty() && current != boundCarId
        boundCarId = current
        tvCarName.text = TokenStore.displayNameOf(current)
        if (changed) {
            // 换车了，温度选择器交回车况同步
            acTempTouched = false
            loadCarImage(forceRefresh = true)
            refreshAll()
        }
        // 从信息中心返回时，把「我的」页的未读角标对齐到最新
        if (currentTab == 2 && ::minePage.isInitialized) minePage.refreshInfoBadge()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::minePage.isInitialized) minePage.destroy()
    }

    private fun currentCarId(): String = TokenStore.xiaoanCarId.ifEmpty { TokenStore.carId }

    /**
     * 尝试 MQTT 车况通道。成功则直接渲染并返回 true。
     *
     * 只有 mqtt 协议车型（REST 车况返回空 `params` 的那种）才会有数据。
     */
    private suspend fun tryMqttStatus(carId: String): Boolean {
        tvUpdated.text = "正在通过实时推送通道获取…"
        val pushed = withContext(Dispatchers.IO) {
            runCatching { MqttCarStatus.fetchStatus(carId, this@DashboardActivity) }.getOrNull()
        }
        if (pushed == null || pushed.length() == 0) return false
        renderStatus(pushed, force = true)
        refreshLocation(pushed)
        tvUpdated.text = "${pushed.optString("terminalTime").ifEmpty { "--:--" }} 更新 · 实时推送"
        return true
    }

    /** 调试入口：单独跑一次 MQTT 车况通道 */
    private fun testMqttChannel() {
        Toast.makeText(this, "测试 MQTT 通道…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { MqttCarStatus.fetchStatus(TokenStore.carId, this@DashboardActivity) }.getOrNull()
            }
            Toast.makeText(
                this@DashboardActivity,
                if (r == null) "MQTT 未取到数据（看 Logcat MqttCarStatus）"
                else "MQTT 成功：${r.length()} 个字段",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun bindViews() {
        tvCarName = findViewById(R.id.tvCarName)
        tvOnline = findViewById(R.id.tvOnline)
        // 调试构建下点按右上角状态可单独测一次 MQTT 车况通道
        if (BuildConfig.DEBUG) {
            tvOnline.setOnClickListener { testMqttChannel() }
        }
        tvUpdated = findViewById(R.id.tvUpdated)
        tvRange = findViewById(R.id.tvRange)
        tvFuel = findViewById(R.id.tvFuel)
        pillLock = findViewById(R.id.pillLock)
        pillEngine = findViewById(R.id.pillEngine)
        pillWindow = findViewById(R.id.pillWindow)
        tvStLock = findViewById(R.id.tvStLock)
        tvStDoor = findViewById(R.id.tvStDoor)
        tvStWindow = findViewById(R.id.tvStWindow)
        tvStSunroof = findViewById(R.id.tvStSunroof)
        tvStTrunk = findViewById(R.id.tvStTrunk)
        tvStHood = findViewById(R.id.tvStHood)
        tvStAc = findViewById(R.id.tvStAc)
        tvStTyreF = findViewById(R.id.tvStTyreF)
        tvStTyreR = findViewById(R.id.tvStTyreR)
        tvStBattery = findViewById(R.id.tvStBattery)
        tvStPm25 = findViewById(R.id.tvStPm25)
        tvStInTemp = findViewById(R.id.tvStInTemp)
        tvStOdometer = findViewById(R.id.tvStOdometer)
        tvStFuelEco = findViewById(R.id.tvStFuelEco)
        tvStKey = findViewById(R.id.tvStKey)
        tvStWater = findViewById(R.id.tvStWater)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        contentScroll = findViewById(R.id.contentScroll)
        tvAddress = findViewById(R.id.tvAddress)
        tvLocMeta = findViewById(R.id.tvLocMeta)
        pageStatus = findViewById(R.id.pageStatus)
        pageControl = findViewById(R.id.pageControl)
        pageMine = findViewById(R.id.pageMine)
    }

    private fun buildListeners() {
        findViewById<View>(R.id.navStatus).setOnClickListener { switchTab(0) }
        findViewById<View>(R.id.navControl).setOnClickListener { switchTab(1) }
        findViewById<View>(R.id.navMine).setOnClickListener { switchTab(2) }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshAll(showTip = true) }

        setupControlGrid()

        findViewById<View>(R.id.cardLocation).setOnClickListener { openMap() }

        // 下拉刷新
        swipeRefresh.setColorSchemeColors(getColor(R.color.brand))
        swipeRefresh.setOnRefreshListener { refreshAll(showTip = true) }
    }

    private fun stepTemp(delta: Int) {
        acTemp = (acTemp + delta).coerceIn(16, 30)
        acTempTouched = true
        tileAdapter.airTempText = "$acTemp°C"
    }

    /**
     * 按登录通道点亮按钮：
     * 扫码授权（VCS）支持全部指令；手机号通道只有 `XiaoanApi.toXiaoanCmd` 能映射的那几个，
     * 其余按钮置灰（仍可点击，点了会说明需要扫码授权）。
     */
    private fun applyChannelCapability() {
        if (::tileAdapter.isInitialized) tileAdapter.refreshUsable()
        val full = TokenStore.hasToken
        val acUsable = full || XiaoanApi.toXiaoanCmd(ControlAction.OPEN_AIR) != null
        if (::tileAdapter.isInitialized) tileAdapter.airUsable = acUsable
    }

    /**
     * 三个 tab 全部在同一个 Activity 里显隐切换（我的页是 include 进来的视图），
     * 没有任何 Activity 跳转，所以三个页面的手感完全一致。
     */
    @SuppressLint("UseCompatLoadingForColorStateLists")
    private fun switchTab(index: Int) {
        val changed = index != currentTab
        currentTab = index

        pageStatus.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageControl.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageMine.visibility = if (index == 2) View.VISIBLE else View.GONE

        // 我的页自带 ScrollView，下拉刷新只留给车况那两页
        swipeRefresh.isEnabled = index != 2
        // 仪表盘/控车共用一个 ScrollView，换页时回到顶部，别停在上一页的滚动位置
        if (changed && index != 2) contentScroll.post { contentScroll.scrollTo(0, 0) }

        val brand = getColor(R.color.brand)
        val tertiary = getColor(R.color.txt_tertiary)
        val secondary = getColor(R.color.txt_secondary)

        val statusIcon = findViewById<ImageView>(R.id.navStatusIcon)
        val controlIcon = findViewById<ImageView>(R.id.navControlIcon)
        val mineIcon = findViewById<ImageView>(R.id.navMineIcon)
        val statusLabel = findViewById<TextView>(R.id.navStatusLabel)
        val controlLabel = findViewById<TextView>(R.id.navControlLabel)
        val mineLabel = findViewById<TextView>(R.id.navMineLabel)

        statusIcon.setColorFilter(if (index == 0) brand else tertiary)
        controlIcon.setColorFilter(if (index == 1) brand else tertiary)
        mineIcon.setColorFilter(if (index == 2) brand else tertiary)
        statusLabel.setTextColor(if (index == 0) brand else secondary)
        controlLabel.setTextColor(if (index == 1) brand else secondary)
        mineLabel.setTextColor(if (index == 2) brand else secondary)

        if (index == 2) minePage.onShown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    /** 「我的」页切车 / 改名后回调：车名、车图、车况全部跟着换 */
    private fun onCarSwitched() {
        boundCarId = currentCarId()
        tvCarName.text = TokenStore.displayNameOf(boundCarId)
        acTempTouched = false
        loadCarImage(forceRefresh = true)
        refreshAll()
    }

    /** 在「我的」页按返回先回仪表盘，而不是直接退出 Activity */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentTab == 2) {
            switchTab(0)
            return
        }
        super.onBackPressed()
    }

    /**
     * 车辆外观图完全由服务端按车型下发（/app2/api/resource/list?pubCode=CARPNG，
     * 响应里的 block.name 就是车型代号，如 CARPNG!C281 = UNI-V）。
     * 本地只做**按 carId 分文件**的缓存；没有缓存时显示中性占位，不硬编码任何车型图。
     */
    private fun loadCarImage(forceRefresh: Boolean = false) {
        val iv = findViewById<ImageView>(R.id.ivCar)
        val carId = TokenStore.xiaoanCarId.ifEmpty { TokenStore.carId }
        if (carId.isEmpty()) return

        val cache = XiaoanApi.carImageCache(filesDir, carId)
        val hadCache = cache.exists() && cache.length() > 1024
        if (hadCache) showCarImage(iv, cache)

        if (!TokenStore.hasXiaoan || TokenStore.xiaoanToken.isEmpty()) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { XiaoanApi.ensureCarImage(cache, carId, forceRefresh) }.getOrDefault(false)
            }
            if (ok && (!hadCache || forceRefresh)) showCarImage(iv, cache)
        }
    }

    private fun showCarImage(iv: ImageView, file: java.io.File) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()?.let { iv.setImageBitmap(it) } ?: iv.setImageResource(R.drawable.ic_car_placeholder)
    }

    private fun refreshAll(showTip: Boolean = false) {
        if (refreshing) {
            swipeRefresh.isRefreshing = false
            return
        }
        refreshing = true
        if (showTip) Toast.makeText(this, "正在刷新车况…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                // 小程序 token 临近过期时先刷新一次，避免首屏请求必然失败再重试；
                // 未记录过期时间的老数据（值为 0）也会走到这里补一次
                if (TokenStore.hasXiaoan) {
                    val left = TokenStore.xiaoanTokenExpireAt - System.currentTimeMillis() / 1000
                    if (left < 300) withContext(Dispatchers.IO) { XiaoanApi.refreshToken() }
                }
                // 老用户升级：已有手机号 token 但还没有 VCS token 时，先补一次免扫码授权，
                // 成功就直接走全功能通道。
                if (!TokenStore.hasToken && TokenStore.hasXiaoan) {
                    val err = withContext(Dispatchers.IO) { ChanganAuth.authorizeByXiaoanTokenDetail() }
                    if (err == null) {
                        applyChannelCapability()
                        Toast.makeText(this@DashboardActivity, "已获取全功能通道", Toast.LENGTH_SHORT).show()
                    } else {
                        // 授权失败会让车况退化到手机号通道，部分车型取不到数据，必须提示
                        Toast.makeText(
                            this@DashboardActivity,
                            "全功能通道获取失败：$err",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                if (TokenStore.hasToken) {
                    refreshViaVcs()
                } else {
                    refreshViaXiaoan()
                }
            } catch (e: Exception) {
                tvOnline.text = "● 离线"
                tvUpdated.text = e.message ?: "加载失败"
            } finally {
                refreshing = false
                swipeRefresh.isRefreshing = false
            }
        }
    }

    /** 扫码通道：open.iov VCS，能拿实时车况 + 控车 */
    private suspend fun refreshViaVcs() {
        val expiringSoon = TokenStore.tokenExpireAt - System.currentTimeMillis() / 1000 < 300
        if (expiringSoon) withContext(Dispatchers.IO) { ChanganAuth.refresh() }

        if (TokenStore.carId.isEmpty()) {
            val cars: JSONArray = withContext(Dispatchers.IO) { ChanganAuth.fetchCars() }
            if (cars.length() == 0) {
                clearStatus("未找到已授权车辆")
                return
            }
            val car = cars.getJSONObject(0)
            TokenStore.carId = car.optString("carId")
            TokenStore.carVin = car.optString("vin")
        }
        tvCarName.text = TokenStore.displayNameOf(TokenStore.carId, TokenStore.carVin)

        val res = withContext(Dispatchers.IO) {
            runCatching { ControlActions.fetchStatus().getOrThrow() }
        }
        val status = res.getOrNull()
        if (status != null && status.length() > 0) {
            renderStatus(status)
            refreshLocation(status)
            return
        }
        // 扫码通道拿不到车况时，回退手机号通道，避免不同协议车型直接空白
        // 取车况失败时把协议版本一并带出来：不同车型协议不同，小程序通道不一定支持
        val protocol = withContext(Dispatchers.IO) {
            runCatching { ChanganAuth.fetchProtocolType(TokenStore.carId) }.getOrDefault("")
        }
        val reason = res.exceptionOrNull()?.message.orEmpty()
        android.util.Log.i(
            "Changan",
            "VCS 车况失败 carId=${TokenStore.carId} reason=$reason protocol=$protocol",
        )
        // 没有手机号通道时直接试 MQTT，避免「VCS 失败就结束」
        if (TokenStore.xiaoanToken.isEmpty()) {
            if (tryMqttStatus(TokenStore.carId)) return
            tvOnline.text = "● 离线"
            tvUpdated.text = "车况读取失败：$reason"
            return
        }
        refreshViaXiaoan(
            if (protocol.isEmpty()) reason else "$reason，协议 $protocol",
        )
    }

    /**
     * 手机号通道：小安控车接口。
     * `/app2/api/car/data` 返回的就是**实时车况**（字段与 open.iov 一一对应），
     * 所以手机号登录同样能看完整车况、车图，并用控车码下发指令。
     */
    private suspend fun refreshViaXiaoan(vcsReason: String = "") {
        if (TokenStore.xiaoanToken.isEmpty()) {
            tvOnline.text = "● 离线"
            tvUpdated.text = "车况读取失败：" + vcsReason.ifEmpty { "未登录小安通道" }
            return
        }
        if (TokenStore.xiaoanCarId.isEmpty()) {
            val cars: JSONArray = withContext(Dispatchers.IO) { XiaoanApi.fetchCars() }
            if (cars.length() == 0) {
                clearStatus("该手机号下没有已授权车辆")
                return
            }
            val car = cars.getJSONObject(0)
            TokenStore.xiaoanCarId = car.optString("carId")
            TokenStore.xiaoanCarName = car.optString("carName")
            TokenStore.carId = car.optString("carId")
            TokenStore.carVin = car.optString("vin").ifEmpty { car.optString("plateNo") }
        }

        val carId = TokenStore.xiaoanCarId
        tvCarName.text = TokenStore.displayNameOf(carId, TokenStore.carVin)
        loadCarImage()

        val statusRes = withContext(Dispatchers.IO) {
            runCatching { XiaoanApi.fetchCarStatus(carId) }
        }
        val status = statusRes.getOrNull()
        if (status == null || status.length() == 0) {
            // REST 两条通道都拿不到：最后试 MQTT 实时推送（mqtt 协议车型只走这个）
            if (tryMqttStatus(carId)) return
            tvOnline.text = "● 离线"
            val err = statusRes.exceptionOrNull()?.message
            tvUpdated.text = when {
                err == null -> "该车型车况需实时推送，暂未取到数据"
                vcsReason.isNotEmpty() -> "车况读取失败：$err（扫码通道：$vcsReason）"
                else -> "车况读取失败：$err"
            }
            return
        }
        renderStatus(status)
        refreshLocation(status)
        tvUpdated.text = "${status.optString("terminalTime").ifEmpty { "--:--" }} 更新 · 手机号"
    }

    /** 控车后刷新车况：优先用扫码通道，否则走手机号通道 */
    private suspend fun refreshStatus() {
        if (TokenStore.hasToken) {
            runCatching {
                val s: JSONObject = withContext(Dispatchers.IO) { ControlActions.fetchStatus().getOrThrow() }
                // 控车后强制重绘：状态可能刚变，不能因为标识相同就跳过
                renderStatus(s, force = true)
                refreshLocation(s)
            }
            return
        }
        val carId = TokenStore.xiaoanCarId.ifEmpty { TokenStore.carId }
        if (carId.isEmpty()) return
        runCatching {
            val s: JSONObject = withContext(Dispatchers.IO) { XiaoanApi.fetchCarStatus(carId) }
            if (s.length() > 0) {
                renderStatus(s)
                refreshLocation(s)
                tvUpdated.text = "${s.optString("terminalTime").ifEmpty { "--:--" }} 更新 · 手机号"
            }
        }
    }

    /**
     * 车辆位置。两条来源：
     * - VCS 车况 `get-car-condition-map` 自带 `lat/lng/gpsTime/validGps`，但**没有地址文本**；
     * - 小安 `GET /app2/api/car/location`（`mapType=GCJ02`）能拿到 `address/city/adCode/heading/speed`。
     * 所以有手机号 token 时补一次地址，扫码通道退化为显示坐标。
     */
    private suspend fun refreshLocation(status: JSONObject?) {
        var lat = status?.optDouble("lat", 0.0) ?: 0.0
        var lng = status?.optDouble("lng", 0.0) ?: 0.0
        var gpsTime = status?.optString("gpsTime").orEmpty()
        var valid = status?.optBoolean("validGps", true) ?: true
        var speed = status?.optDouble("speed", -1.0) ?: -1.0
        var address = ""
        var city = ""

        val carId = TokenStore.xiaoanCarId.ifEmpty { TokenStore.carId }
        if (TokenStore.hasXiaoan && TokenStore.xiaoanToken.isNotEmpty() && carId.isNotEmpty()) {
            val loc = withContext(Dispatchers.IO) {
                runCatching { XiaoanApi.fetchLocation(carId) }.getOrNull()
            }
            if (loc != null && loc.length() > 0) {
                if (loc.optDouble("lat", 0.0) != 0.0) lat = loc.optDouble("lat")
                if (loc.optDouble("lng", 0.0) != 0.0) lng = loc.optDouble("lng")
                address = loc.optString("address")
                city = loc.optString("city")
                gpsTime = loc.optString("gpsTime").ifEmpty { gpsTime }
                valid = loc.optBoolean("validGps", valid)
                val s = loc.optDouble("speed", -1.0)
                if (s >= 0) speed = s
            }
        }
        renderLocation(lat, lng, address, city, gpsTime, speed, valid)
    }

    private fun renderLocation(
        lat: Double,
        lng: Double,
        address: String,
        city: String,
        gpsTime: String,
        speed: Double,
        valid: Boolean,
    ) {
        lastLat = lat
        lastLng = lng
        hasLocation = valid && lat != 0.0 && lng != 0.0

        tvAddress.text = when {
            !hasLocation -> "暂无有效定位"
            address.isNotEmpty() -> address
            city.isNotEmpty() -> city
            else -> "%.5f, %.5f".format(lat, lng)
        }
        tvLocMeta.text = buildString {
            if (hasLocation) append("%.5f, %.5f".format(lat, lng))
            if (gpsTime.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(gpsTime)
            }
            if (hasLocation && speed >= 0) {
                if (isNotEmpty()) append(" · ")
                append("%.0f km/h".format(speed))
            }
        }.ifEmpty { "--" }
    }

    /** 打开地图：优先高德 URI，失败退 geo: */
    /**
     * 打开车辆位置，按「装了谁就用谁」的顺序直连：
     * 高德 App → 百度地图 → 腾讯地图 → 高德网页 → 系统 geo:。
     *
     * 车况给的是 GCJ02（火星坐标，高德那套），百度要显式声明 coord_type=gcj02，
     * 否则会有几百米的偏移。
     */
    private fun openMap() {
        android.util.Log.d("MapOpen", "click hasLocation=$hasLocation lat=$lastLat lng=$lastLng")
        if (!hasLocation) {
            Toast.makeText(this, "暂无有效定位", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "车辆位置"
        val lat = lastLat
        val lng = lastLng

        // 1. 高德地图 App
        if (openUri(
                "androidamap://viewMap?sourceApplication=ChanganPanel&poiname=$name&lat=$lat&lon=$lng&dev=0",
                "com.autonavi.minimap",
            )
        ) return

        // 2. 百度地图
        if (openUri(
                "baidumap://map/marker?location=$lat,$lng&title=$name&content=$name&coord_type=gcj02&zoom=16&src=android.io.github.aw1y2z.changan",
                "com.baidu.BaiduMap",
            )
        ) return

        // 3. 腾讯地图
        if (openUri(
                "qqmap://map/marker?marker=coord:$lat,$lng;title:$name;addr=$name&referer=ChanganPanel",
                "com.tencent.map",
            )
        ) return

        // 4. 高德网页（callnative=1 仍会尝试唤起 App）
        if (openUri(
                "https://uri.amap.com/marker?position=$lng,$lat&name=$name&coordinate=gaode&callnative=1",
                null,
            )
        ) return

        // 5. 系统地图选择器
        if (!openUri("geo:$lat,$lng?q=$lat,$lng($name)", null)) {
            Toast.makeText(this, "没有可用的地图应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 指定包名时只交给那一个 App；没装会抛异常，按失败处理 */
    private fun openUri(uri: String, pkg: String?): Boolean = runCatching {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(uri),
        )
        if (pkg != null) intent.setPackage(pkg)
        startActivity(intent)
        android.util.Log.d("MapOpen", "ok: ${pkg ?: "system"} ${uri.take(60)}")
        true
    }.getOrElse {
        android.util.Log.d("MapOpen", "fail: ${pkg ?: "system"} ${it.javaClass.simpleName} ${it.message}")
        false
    }

    /** 上次渲染用的车况标识，用于判断车端是否真的上报了新数据 */
    private var lastStatusKey = ""

    private fun renderStatus(status: JSONObject, force: Boolean = false) {
        // `terminalTime` + `requestId` 都没变，说明车端没有新上报：
        // 跳过重绘可避免界面闪动，也不会重复往信息中心推同一条异常
        val key = status.optString("terminalTime") + "|" + status.optString("requestId")
        if (!force && key.length > 1 && key == lastStatusKey) {
            tvOnline.text = "● 在线"
            return
        }
        lastStatusKey = key
        tvOnline.text = "● 在线"
        tvUpdated.text = "${status.optString("terminalTime").ifEmpty { "--:--" }} 更新"
        // 信息中心：同一份车况顺手做一次本地检测（未锁车、车窗、胎压、点熄火），有新异常就推通知
        scope.launch(Dispatchers.IO) {
            val added = InfoCenter.observe(applicationContext, status)
            NotifyCenter.push(applicationContext, added)
            // 官方的智能守护（车门未闭锁等）是车端上报的，本地推断不出来，这里顺手查一次
            runCatching { SafetyCheck.pushNewOfficial(applicationContext) }
        }

        // 顶部两格：续航 + 油量，都是「还能跑多远」的决策信息，低于阈值标橙
        val range = status.optInt("remainedOilMile", 0)
        setStatValue(tvRange, if (range <= 0) "--" else "$range", "km", warn = range in 1..50)

        val fuel = status.optDouble("fuelLeftover", 0.0)
        setStatValue(tvFuel, "%.0f".format(fuel), "L", warn = fuel in 1.0..10.0)

        // 胶囊：车锁 / 发动机 / 车窗，正常绿、异常橙、未知灰
        val locked = status.optInt("passengerDoorLock", -1)
        setPill(
            pillLock,
            if (locked < 0) null else locked == 0,
            if (locked < 0) "车锁 --" else if (locked == 0) "已上锁" else "未上锁",
        )

        // engineStatus：1=启动，2=熄火，其它按未知
        when (status.optInt("engineStatus", -1)) {
            1 -> setPill(pillEngine, true, "已启动")
            2 -> setPill(pillEngine, true, "已熄火")
            else -> setPill(pillEngine, null, "发动机 --")
        }

        val closed = windowsClosed(status)
        setPill(pillWindow, closed, if (closed == null) "车窗 --" else if (closed) "车窗已关" else "车窗未关")

        // 2×8 网格：上半屏看开合状态，下半屏看数据
        setSt(tvStLock, if (locked < 0) "--" else if (locked == 0) "已上锁" else "未上锁", abnormal = locked > 0)

        val doorsClosed = allOff(
            status,
            listOf("driverDoor", "passengerDoor", "leftRearDoor", "rightRearDoor"),
        )
        setSt(
            tvStDoor,
            if (doorsClosed == null) "--" else if (doorsClosed) "已关" else "未关",
            abnormal = doorsClosed == false,
        )

        setSt(tvStWindow, if (closed == null) "--" else if (closed) "关闭" else "开启", abnormal = closed == false)

        val sunroof = status.optInt("sunroof", -1)
        setSt(tvStSunroof, if (sunroof < 0) "--" else if (sunroof == 0) "关闭" else "打开", abnormal = sunroof > 0)

        val trunk = status.optInt("trunk", -1)
        setSt(tvStTrunk, if (trunk < 0) "--" else if (trunk == 0) "关闭" else "打开", abnormal = trunk > 0)

        val hood = status.optInt("hood", -1)
        setSt(tvStHood, if (hood < 0) "--" else if (hood == 0) "关闭" else "打开", abnormal = hood > 0)

        // 空调：设定温度 + 风量档位（未开启时档位没意义，不显示）
        val acTemp = status.optDouble("airConditioningSetTemperature", 0.0)
        val air = status.optJSONObject("air")
        val acOn = air?.optString("status")?.let { it != "0" } ?: false
        val wind = air?.optString("wind")?.toIntOrNull() ?: 0
        setSt(
            tvStAc,
            when {
                acTemp <= 0 -> "--"
                acOn && wind > 0 -> "%.0f℃ · %d档".format(acTemp, wind)
                acOn -> "%.0f℃ 开".format(acTemp)
                else -> "%.0f℃".format(acTemp)
            },
        )

        // 电瓶电压：熄火状态低于 11.8 V 说明电瓶偏亏
        val battery = status.optDouble("batteryVoltage", 0.0)
        setSt(
            tvStBattery,
            if (battery <= 0) "--" else "%.1f V".format(battery),
            abnormal = battery in 0.1..11.7,
        )

        // 胎压：同一行左右对比，前轮 lf/rf、后轮 lr/rr，单轮亏气一眼能看出
        val lf = status.optInt("lfTyrePressure", 0)
        val rf = status.optInt("rfTyrePressure", 0)
        val lr = status.optInt("lrTyrePressure", 0)
        val rr = status.optInt("rrTyrePressure", 0)
        setSt(tvStTyreF, tyrePair(lf, rf), abnormal = tyreBad(lf) || tyreBad(rf))
        setSt(tvStTyreR, tyrePair(lr, rr), abnormal = tyreBad(lr) || tyreBad(rr))

        val pm = status.optInt("pm25", 0)
        setSt(tvStPm25, if (pm <= 0) "0" else "$pm", abnormal = pm > 75)

        // 车内 / 车外合并成一格：对照空调设定看温差
        val inTemp = status.optDouble("vehicleTemperature", 0.0)
        val outTemp = status.optDouble("environmentalTemp", 0.0)
        val inText = if (inTemp == 0.0) "--" else "%.0f℃".format(inTemp)
        val outText = if (outTemp == 0.0) "--" else "%.0f℃".format(outTemp)
        setSt(tvStInTemp, "$inText / $outText")

        val odo = status.optDouble("totalOdometer", 0.0)
        setSt(tvStOdometer, if (odo <= 0) "--" else "%.0f km".format(odo))

        val eco = status.optDouble("fuelConsumption100km", 0.0)
        setSt(tvStFuelEco, if (eco <= 0) "--" else "%.1f L/100km".format(eco))

        // 钥匙电量：keyLowPower 0=正常
        val keyLow = status.optInt("keyLowPower", -1)
        setSt(tvStKey, if (keyLow < 0) "--" else if (keyLow == 0) "正常" else "电量低", abnormal = keyLow > 0)

        // 水温：正常 85~95℃，超过 100℃ 才算异常，平时只是一行灰字
        val water = status.optDouble("engineWaterTemp", 0.0)
        setSt(tvStWater, if (water <= 0) "--" else "%.1f℃".format(water), abnormal = water > 100)

        // 温度选择器跟车况对齐；用户手动调过就不再回写，避免刚调好被覆盖
        if (!acTempTouched && acTemp >= 16) {
            this.acTemp = Math.round(acTemp).toInt().coerceIn(16, 30)
            tileAdapter.airTempText = "${this.acTemp}°C"
        }
        renderAcNow(acOn, wind, hasTemp = acTemp > 0)
    }

    /** 控车页空调条的状态文案：空调已关 / 空调开 · 3档 */
    private fun renderAcNow(on: Boolean, wind: Int, hasTemp: Boolean) {
        tileAdapter.airStatusText = when {
            !hasTemp -> "空调 --"
            !on -> "空调已关"
            wind > 0 -> "空调开 · ${wind}档"
            else -> "空调开"
        }
    }

    /** 一组开关量是否全为 0（关/正常）。缺字段跳过，一个可用字段都没有时返回 null。 */
    private fun allOff(status: JSONObject, keys: List<String>): Boolean? {
        var seen = false
        for (k in keys) {
            val v = status.optInt(k, -1)
            if (v < 0) continue
            seen = true
            if (v != 0) return false
        }
        return if (seen) true else null
    }

    /** 左右轮压：都缺显示 --，单侧缺显示 -- */
    private fun tyrePair(left: Int, right: Int): String {
        if (left <= 0 && right <= 0) return "--"
        val l = if (left <= 0) "--" else left.toString()
        val r = if (right <= 0) "--" else right.toString()
        return "$l / $r"
    }

    /** 胎压异常：低于 180 kPa（亏气）或高于 320 kPa（过充） */
    private fun tyreBad(v: Int) = v in 1..179 || v > 320

    /**
     * 车窗是否全关。车况里每个窗一个字段（0=关闭），缺字段的直接跳过；
     * 一个可用字段都没有时返回 null（未知）。
     */
    private fun windowsClosed(status: JSONObject): Boolean? = allOff(
        status,
        listOf(
            "diverWindow", "passengerWindow", "leftFrontDoorrWindow",
            "leftRearDoorrWindow", "rightFrontDoorrWindow", "rightRearWindow",
        ),
    )

    /** 胶囊三态：true=正常(绿) false=异常(橙) null=未知(灰) */
    private fun setPill(tv: TextView, ok: Boolean?, text: String) {
        tv.text = text
        tv.setBackgroundResource(
            when (ok) {
                true -> R.drawable.bg_pill_ok
                false -> R.drawable.bg_pill_warn
                null -> R.drawable.bg_pill_idle
            },
        )
        tv.setTextColor(
            getColor(
                when (ok) {
                    true -> R.color.accent
                    false -> R.color.warn
                    null -> R.color.txt_secondary
                },
            ),
        )
    }

    /** 顶部两格标签行：只写中文，单位跟着数值一起显示 */
    private fun setupStatLabels() {
        findViewById<TextView>(R.id.tvLabelRange).text = "续航"
        findViewById<TextView>(R.id.tvLabelFuel).text = "油量"
    }

    /** 顶部两格数值：单位跟在数字后面、字号略小，数字保持 20sp 的视觉重量 */
    private fun setStatValue(tv: TextView, value: String, unit: String, warn: Boolean) {
        if (value == "--") {
            tv.text = value
        } else {
            val text = SpannableString("$value $unit")
            text.setSpan(
                RelativeSizeSpan(0.62f),
                value.length + 1,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            tv.text = text
        }
        tv.setTextColor(getColor(if (warn) R.color.warn else R.color.txt_primary))
    }

    private fun setSt(tv: TextView, text: String, abnormal: Boolean = false) {
        tv.text = text
        tv.setTextColor(getColor(if (abnormal) R.color.warn else R.color.txt_primary))
    }

    /** 没车 / 读不到车况时，把仪表盘清成占位 */
    private fun clearStatus(msg: String) {
        tvOnline.text = "● 离线"
        tvUpdated.text = msg
        setPill(pillLock, null, "车锁 --")
        setPill(pillEngine, null, "发动机 --")
        setPill(pillWindow, null, "车窗 --")
        if (::tileAdapter.isInitialized) tileAdapter.airStatusText = "空调 --"
        setSt(tvRange, "--")
        setSt(tvFuel, "--")
        listOf(
            tvStLock, tvStDoor, tvStWindow, tvStSunroof, tvStTrunk, tvStHood, tvStAc,
            tvStTyreF, tvStTyreR, tvStBattery, tvStPm25, tvStInTemp, tvStOdometer,
            tvStFuelEco, tvStKey, tvStWater,
        ).forEach { setSt(it, "--") }
    }

    private fun confirm(action: ControlAction, temperature: String? = null) {
        val detail = if (action == ControlAction.OPEN_AIR) "，设定 $acTemp°C" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(action.label)
            .setMessage("确认执行「${action.label}」$detail？")
            .setPositiveButton("确认") { _, _ -> doControl(action, temperature) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doControl(action: ControlAction, temperature: String? = null) {
        val target = actionView(action)
        // 上一次还没回来（按钮被 setBusy 禁用）就忽略，避免连点下发多条指令
        if (target != null && !target.isEnabled) return
        when {
            TokenStore.hasToken -> sendViaVcs(action, temperature, target)
            TokenStore.hasXiaoan -> sendViaXiaoan(action, target)
            else -> MaterialAlertDialogBuilder(this)
                .setTitle("无法下发指令")
                .setMessage("请先用手机号 + 短信验证码登录，或扫码授权。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /**
     * 控车功能块网格：12 列，每块默认占 3 列（一行四个），
     * 长按进入编辑态后可拖拽排序、点右上角切换 1/4 · 1/3 · 1/2 宽度。
     */
    private fun setupControlGrid() {
        rvControl = findViewById(R.id.rvControl)
        controlOrder.clear()
        controlOrder.addAll(loadControlOrder())

        tileAdapter = ControlTileAdapter(
            controlOrder,
            isUsable = { action -> TokenStore.hasToken || XiaoanApi.toXiaoanCmd(action) != null },
            onClick = { action -> if (action in needConfirm) confirm(action) else doControl(action) },
            onResize = { tile ->
                val action = tile.action
                if (action != null) {
                    val span = tileAdapter.cycleSpan(tile)
                    saveControlOrder(tileAdapter.currentOrder())
                    Toast.makeText(
                        this,
                        "${ControlTileAdapter.shortLabelOf(action)} · ${ControlTileAdapter.spanLabel(span)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onExitEdit = { exitControlEdit() },
            onAirTemp = { delta -> stepTemp(delta) },
            onAirOn = { confirm(ControlAction.OPEN_AIR, acTemp.toString()) },
            onAirOff = { doControl(ControlAction.CLOSE_AIR) },
        )
        tileAdapter.rvRef = rvControl
        tileAdapter.airTempText = "$acTemp°C"

        val glm = androidx.recyclerview.widget.GridLayoutManager(this, 12)
        glm.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = controlOrder[position].span
        }
        rvControl.layoutManager = glm
        rvControl.adapter = tileAdapter
        rvControl.itemAnimator = null
        attachDragSort()

        // 编辑态提示条，点它退出（RecyclerView 会吃掉空白点击，所以给个明确入口）
        findViewById<TextView>(R.id.tvEditBar).setOnClickListener { exitControlEdit() }
    }

    private fun exitControlEdit() {
        if (!::tileAdapter.isInitialized || !tileAdapter.editing) return
        tileAdapter.editing = false
        saveControlOrder(tileAdapter.currentOrder())
        findViewById<TextView>(R.id.tvEditBar).visibility = View.GONE
    }

    /** 长按功能块进入编辑态并开始拖拽 */
    private fun attachDragSort() {
        val cb = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                androidx.recyclerview.widget.ItemTouchHelper.START or
                androidx.recyclerview.widget.ItemTouchHelper.END,
            0,
        ) {
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ): Boolean {
                android.util.Log.d("DragSort", "onMove ${vh.bindingAdapterPosition} -> ${target.bindingAdapterPosition}")
                tileAdapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                state: Int,
            ) {
                super.onSelectedChanged(vh, state)
                if (state != androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) return
                android.util.Log.d("DragSort", "开始拖拽 pos=${vh?.bindingAdapterPosition}")
                vh?.itemView?.let {
                    it.alpha = 0.85f
                    it.scaleX = 1.06f
                    it.scaleY = 1.06f
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                // 拖拽期间不许父级（SwipeRefreshLayout / ScrollView）抢竖直滑动
                swipeRefresh.isEnabled = false
                contentScroll.requestDisallowInterceptTouchEvent(true)
                tileAdapter.editing = true
                findViewById<TextView>(R.id.tvEditBar).visibility = View.VISIBLE
                if (!editTipShown) {
                    editTipShown = true
                    Toast.makeText(
                        this@DashboardActivity,
                        "拖动排序 · 点右上角调宽度 · 点空白完成",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun clearView(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) {
                super.clearView(rv, vh)
                android.util.Log.d("DragSort", "拖拽结束")
                vh.itemView.alpha = 1f
                vh.itemView.scaleX = 1f
                vh.itemView.scaleY = 1f
                swipeRefresh.isEnabled = currentTab != 2
                contentScroll.requestDisallowInterceptTouchEvent(false)
                saveControlOrder(tileAdapter.currentOrder())
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(cb).attachToRecyclerView(rvControl)
    }

    /**
     * 读本机配置，格式 "AIR:12,LOCK:3,..."；没存过就用默认顺序。
     * 空调（AIR）固定整行，但位置可拖。
     */
    private fun loadControlOrder(): List<ControlTile> {
        val all = ControlAction.values().toList()
            .filter { it != ControlAction.OPEN_AIR && it != ControlAction.CLOSE_AIR }
        val saved = getSharedPreferences("control_tiles", MODE_PRIVATE)
            .getString("order", null)

        val default = ArrayList<ControlTile>(all.size + 1)
        default.add(ControlTile(null, ControlTileAdapter.SPAN_FULL))
        all.forEach { default.add(ControlTile(it)) }
        if (saved.isNullOrBlank()) return default

        val out = ArrayList<ControlTile>(all.size + 1)
        val seen = HashSet<String>()
        for (entry in saved.split(",")) {
            if (entry.isBlank()) continue
            val parts = entry.split(":")
            val key = parts[0]
            if (!seen.add(key)) continue
            if (key == "AIR") {
                out.add(ControlTile(null, ControlTileAdapter.SPAN_FULL))
                continue
            }
            val action = runCatching { ControlAction.valueOf(key) }.getOrNull() ?: continue
            if (action !in all) continue
            val span = parts.getOrNull(1)?.toIntOrNull() ?: ControlTileAdapter.SPAN_QUARTER
            out.add(ControlTile(action, normalizeSpan(span)))
        }
        // 新版本新增的项补到末尾
        if (!seen.contains("AIR")) out.add(0, ControlTile(null, ControlTileAdapter.SPAN_FULL))
        all.forEach { if (!seen.contains(it.name)) out.add(ControlTile(it)) }
        return out
    }

    private fun normalizeSpan(span: Int): Int = when (span) {
        ControlTileAdapter.SPAN_THIRD, ControlTileAdapter.SPAN_HALF -> span
        else -> ControlTileAdapter.SPAN_QUARTER
    }

    private fun saveControlOrder(order: List<ControlTile>) {
        getSharedPreferences("control_tiles", MODE_PRIVATE).edit()
            .putString(
                "order",
                order.joinToString(",") { "${it.action?.name ?: "AIR"}:${it.span}" },
            )
            .apply()
    }

    /** 指令对应的可点区域：功能块，或空调条的「开 / 关」 */
    private fun actionView(action: ControlAction): View? = when (action) {
        ControlAction.OPEN_AIR -> tileAdapter.airView(open = true)
        ControlAction.CLOSE_AIR -> tileAdapter.airView(open = false)
        else -> {
            val pos = controlOrder.indexOfFirst { it.action == action }
            if (pos < 0) null else rvControl.findViewHolderForAdapterPosition(pos)?.itemView
        }
    }

    /**
     * 执行中：把按钮压暗并禁用，避免下发窗口内被连点。
     * 恢复时回到进入前的透明度（手机号通道不支持的功能块本来就是 0.3）。
     */
    private fun setBusy(view: View?, busy: Boolean) {
        if (view == null) return
        if (busy) {
            view.tag = view.alpha
            view.isEnabled = false
            view.alpha = 0.35f
        } else {
            view.isEnabled = true
            view.alpha = (view.tag as? Float) ?: 1f
            view.tag = null
        }
    }

    /** 扫码通道：open.iov VCS 签名，免控车码 */
    private fun sendViaVcs(action: ControlAction, temperature: String?, busyView: View?) {
        setBusy(busyView, true)
        scope.launch {
            try {
                Toast.makeText(this@DashboardActivity, "「${action.label}」执行中…", Toast.LENGTH_SHORT).show()
                val r = withContext(Dispatchers.IO) {
                    runCatching { ControlActions.send(action, temperature) }.getOrElse {
                        ControlActions.Outcome(false, it.message ?: "执行异常")
                    }
                }
                io.github.aw1y2z.changan.data.ControlLog.add(
                    applicationContext, action.label, r.ok, r.message,
                )
                Toast.makeText(this@DashboardActivity, r.message, Toast.LENGTH_LONG).show()
                if (r.ok) refreshStatus()
            } finally {
                setBusy(busyView, false)
            }
        }
    }

    /**
     * 手机号通道：v3/control/execute，需要控车码。
     * 控车码因人而异，本机没存过就先问用户要（不内置、不上传）。
     */
    private fun sendViaXiaoan(action: ControlAction, busyView: View?) {
        val cmd = XiaoanApi.toXiaoanCmd(action)
        if (cmd == null) {
            val supported = XiaoanApi.XiaoanCmd.values().joinToString("、") { "「${it.label}」" }
            MaterialAlertDialogBuilder(this)
                .setTitle("该通道不支持")
                .setMessage("手机号通道支持：$supported。\n其余指令需要扫码授权（引力域 App）。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        // 控车码策略与官方一致：
        //  - 关掉「控车码开关」→ 不校验，有本地码就用，没有才问一次；
        //  - 开着且有预设 → 免输入直接用；
        //  - 开着且没预设 → 弹窗要一次。
        val proceed: () -> Unit = {
            val saved = TokenStore.xiaoanControlPin
            when {
                !TokenStore.controlPinEnabled && saved.isNotEmpty() ->
                    runXiaoanControl(cmd, saved, busyView)

                saved.isNotEmpty() ->
                    runXiaoanControl(cmd, saved, busyView)

                else ->
                    askControlPin { pin -> runXiaoanControl(cmd, pin, busyView) }
            }
        }

        if (TokenStore.controlPinConfirm) {
            MaterialAlertDialogBuilder(this)
                .setTitle("确认操作")
                .setMessage("确定要执行「${action.label}」吗？")
                .setPositiveButton("确定") { _, _ -> proceed() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            proceed()
        }
    }

    /** 控车码输入框：数字键盘 + 掩码，可选是否记住 */
    private fun askControlPin(onOk: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = android.widget.EditText(this).apply {
            hint = "控车码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
        }
        val remember = android.widget.CheckBox(this).apply {
            text = "记住控车码（只存本机）"
            isChecked = true
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(remember)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("输入控车码")
            .setMessage("控车码是你在「小安控车」里设置的车控密码，用于下发远程指令。")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.isEmpty()) {
                    Toast.makeText(this, "控车码不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (remember.isChecked) TokenStore.xiaoanControlPin = pin
                onOk(pin)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runXiaoanControl(cmd: XiaoanApi.XiaoanCmd, pin: String, busyView: View?) {
        val carId = TokenStore.xiaoanCarId.ifEmpty { TokenStore.carId }
        if (carId.isEmpty()) {
            Toast.makeText(this, "未取到车辆信息", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            setBusy(busyView, true)
            val r = try {
                Toast.makeText(this@DashboardActivity, "「${cmd.label}」执行中…", Toast.LENGTH_SHORT).show()
                withContext(Dispatchers.IO) {
                    runCatching { XiaoanApi.executeControl(carId, pin, cmd) }.getOrElse {
                        XiaoanApi.ControlOutcome(false, it.message ?: "执行异常")
                    }
                }
            } finally {
                setBusy(busyView, false)
            }
            if (!r.ok && r.message.contains("控车码错误")) {
                // 码错了别继续留着，下次重新问
                TokenStore.xiaoanControlPin = ""
            }
            io.github.aw1y2z.changan.data.ControlLog.add(applicationContext, cmd.label, r.ok, r.message)
            Toast.makeText(this@DashboardActivity, r.message, Toast.LENGTH_LONG).show()
            if (r.ok) waitControlResult(carId, cmd, r.taskId, r.requestId)
        }
    }

    /** 轮询下发结果；接口没给任务号就只刷新车况 */
    private suspend fun waitControlResult(
        carId: String,
        cmd: XiaoanApi.XiaoanCmd,
        taskId: String,
        requestId: String,
    ) {
        if (taskId.isEmpty() && requestId.isEmpty()) {
            kotlinx.coroutines.delay(1500)
            refreshStatus()
            return
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(3000)
            val data = withContext(Dispatchers.IO) { XiaoanApi.queryControlResult(carId, taskId, requestId) }
            if (data != null) {
                val state = data.optString("handleStatus").ifEmpty { data.optString("cmdStatus") }
                if (state.equals("Completed", true)) {
                    Toast.makeText(this, "「${cmd.label}」执行成功", Toast.LENGTH_SHORT).show()
                    break
                }
                if (state.isNotEmpty() && !state.equals("Running", true) && !state.equals("initialized", true)) {
                    Toast.makeText(this, "「${cmd.label}」$state", Toast.LENGTH_SHORT).show()
                    break
                }
            }
        }
        refreshStatus()
    }
}
