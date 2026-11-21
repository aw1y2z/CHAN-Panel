package io.github.aw1y2z.changan.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.data.ControlAction
import io.github.aw1y2z.changan.data.ControlActions
import io.github.aw1y2z.changan.data.InformationApi
import io.github.aw1y2z.changan.data.TokenStore
import io.github.aw1y2z.changan.data.XiaoanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 车辆数据页，一个界面四种模式（由 intent extra [EXTRA_MODE] 决定）：
 *
 *  - driving：行车轨迹（`/app2/api/driving`，按日期区间拉行程）
 *  - media：全景照片（`/appserver/api/file/getMediaFilesByUserIdAndCarId`）
 *  - records：操作记录（官方接口服务端 500，先用车控消息 APP-CONTROL 兜底）
 *  - pin：控车码管理（服务端 pinSwitch + 三项本地开关）
 */
class VehicleDataActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_DRIVING = "driving"
        const val MODE_MEDIA = "media"
        const val MODE_RECORDS = "records"
        const val MODE_PIN = "pin"

        /** 进程级缓存：退出页面再进来也能命中 */
        private val cache = HashMap<String, CacheEntry<*>>()

        /** 缩略图内存缓存，同样跨页面保留，避免每次进页面图都闪一下 */
        private val bitmapCache = LruCache<String, Bitmap>(12 * 1024 * 1024)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private var mode = MODE_DRIVING

    /** 行车轨迹查询区间（天） */
    private var drivingDays = 7

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DRIVING
        setContentView(buildRoot(resolveTitle()))
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun resolveTitle(): String = when (mode) {
        MODE_MEDIA -> "全景照片"
        MODE_RECORDS -> "操作记录"
        MODE_PIN -> "控车码管理"
        else -> "行车轨迹"
    }

    // ============ 骨架 ============

    private fun buildRoot(title: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
        ))
        return root
    }

    private fun load(force: Boolean = false) {
        content.removeAllViews()
        when (mode) {
            MODE_DRIVING -> loadDriving(force)
            MODE_MEDIA -> loadMedia(force)
            MODE_RECORDS -> loadRecords(force)
            MODE_PIN -> loadPin()
        }
    }

    private data class CacheEntry<T>(val data: T, val at: Long)

    /**
     * 五分钟内存缓存：切回页面直接命中，不必每次重新拉取。
     *
     * 新增数据（拍照、下拉刷新）时传 `force = true` 绕过。
     * 行车轨迹的区间已是历史数据，命中时间可更长，由调用方给 ttl。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(
        key: String,
        force: Boolean,
        ttlMs: Long = 5 * 60_000L,
        loader: () -> T,
    ): T {
        val now = System.currentTimeMillis()
        if (!force) {
            (cache[key] as? CacheEntry<T>)?.let {
                if (now - it.at < ttlMs) return it.data
            }
        }
        val data = loader()
        cache[key] = CacheEntry(data, now)
        return data
    }

    // ============ 行车轨迹 ============

    private fun loadDriving(force: Boolean) {
        content.addView(filterRow())
        val tip = tip("正在拉取行程…")
        content.addView(tip)

        val carId = TokenStore.carId
        if (carId.isEmpty()) {
            tip.text = "还没有选中车辆"
            return
        }

        val end = Calendar.getInstance()
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(drivingDays - 1)) }
        val startText = dayFmt.format(start.time)
        val endText = dayFmt.format(end.time)

        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    cached("driving_${carId}_$drivingDays", force) {
                        XiaoanApi.fetchDrivingHistory(carId, startText, endText)
                    }
                }
            }
            content.removeView(tip)
            r.onSuccess { list ->
                if (list.isEmpty()) {
                    content.addView(tip("$startText ~ $endText 没有行程记录"))
                    return@onSuccess
                }
                content.addView(tip("$startText ~ $endText 共 ${list.size} 段行程"))
                for (t in list) content.addView(tripCard(t))
            }.onFailure {
                content.addView(tip("拉取失败：${it.message}"))
            }
        }
    }

    private fun filterRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        fun chip(label: String, days: Int) = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(getColor(if (days == drivingDays) R.color.brand else R.color.txt_secondary))
            setBackgroundResource(if (days == drivingDays) R.drawable.bg_pill_ok else R.drawable.bg_pill_idle)
            setOnClickListener {
                drivingDays = days
                load()
            }
        }
        row.addView(chip("近 7 天", 7))
        row.addView(space(dp(8)))
        row.addView(chip("近 30 天", 30))
        return row
    }

    private fun tripCard(t: XiaoanApi.Trip): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        card.addView(TextView(this).apply {
            text = "${t.startAddr.ifEmpty { "未知起点" }} → ${t.endAddr.ifEmpty { "未知终点" }}"
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        card.addView(TextView(this).apply {
            text = "${t.startTime} 出发 · ${t.endTime} 到达"
            setTextColor(getColor(R.color.txt_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(TextView(this).apply {
            val km = String.format(Locale.US, "%.1f", t.mileage)
            val min = t.minutes.toInt()
            val spd = String.format(Locale.US, "%.0f", t.avgSpeed)
            text = "$km km · $min 分钟 · 均速 $spd km/h · 评分 ${t.score}"
            setTextColor(getColor(R.color.txt_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    // ============ 全景照片 ============

    private fun loadMedia(force: Boolean) {
        val capture = TextView(this).apply {
            text = "获取新照片"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_btn_brand)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { capturePhoto(this) }
        }
        val lpBtn = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lpBtn.topMargin = dp(8)
        content.addView(capture, lpBtn)

        val tip = tip("正在拉取照片…")
        content.addView(tip)
        val carId = TokenStore.carId
        if (carId.isEmpty()) {
            tip.text = "还没有选中车辆"
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    cached("media_$carId", force) { XiaoanApi.fetchMediaFiles(carId) }
                }
            }
            content.removeView(tip)
            r.onSuccess { list ->
                if (list.isEmpty()) {
                    content.addView(tip("这台车还没有全景照片。\n在车机上拍摄后，这里会同步显示。"))
                    return@onSuccess
                }
                // 同一次拍摄的 4 张 taskId 相同，按组展示
                val groups = list.groupBy { it.taskId.ifEmpty { it.time } }
                content.addView(tip("共 ${list.size} 张 · ${groups.size} 次拍摄"))
                for ((_, items) in groups) {
                    content.addView(groupHeader(items.first().time))
                    content.addView(mediaRow(items))
                }
            }.onFailure {
                content.addView(tip("拉取失败：${it.message}"))
            }
        }
    }

    /** 远程拍照：下发 photograph 指令，等车端上传后刷新列表 */
    private fun capturePhoto(button: TextView) {
        val carId = TokenStore.carId
        if (carId.isEmpty()) {
            toast("还没有选中车辆")
            return
        }
        // 与控车一致：有本机控车码就直接用，没有就问一次
        val saved = TokenStore.xiaoanControlPin
        if (saved.isNotEmpty()) {
            doPhoto(button, carId, saved)
        } else {
            askPin { pin -> doPhoto(button, carId, pin) }
        }
    }

    private fun doPhoto(button: TextView, carId: String, pin: String) {
        button.isEnabled = false
        button.text = "正在拍照…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { XiaoanApi.photograph(carId, pin) }
            if (!r.ok) {
                if (r.message.contains("控车码错误")) TokenStore.xiaoanControlPin = ""
                toast(r.message)
                button.isEnabled = true
                button.text = "获取新照片"
                return@launch
            }
            toast(r.message)
            button.text = "等待车端上传…"
            // 车端拍完还要上传，等一会儿再强制刷新（绕过缓存）
            kotlinx.coroutines.delay(25_000)
            load(force = true)
        }
    }

    /** 输入 6 位控车码 */
    private fun askPin(onDone: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            hint = "6 位控车码"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("输入控车码")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length != 6) {
                    toast("控车码应为 6 位")
                } else {
                    TokenStore.xiaoanControlPin = pin
                    onDone(pin)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 拍摄时间小标题（同一次拍摄的 4 张共用） */
    private fun groupHeader(time: String): View = TextView(this).apply {
        text = time
        setTextColor(getColor(R.color.txt_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(4), dp(14), dp(4), dp(4))
    }

    /** 一次拍摄的 4 张（左/右/前/后）一行铺开 */
    private fun mediaRow(items: List<XiaoanApi.MediaFile>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (media in items.take(4)) {
            val cell = FrameLayout(this)
            val lp = LinearLayout.LayoutParams(0, dp(86), 1f)
            lp.rightMargin = dp(6)
            cell.layoutParams = lp
            cell.setBackgroundResource(R.drawable.bg_card)
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            cell.addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            cell.setOnClickListener { showFullImage(media.url) }
            row.addView(cell)
            loadThumb(media.thumb, iv)
        }
        return row
    }

    private fun loadThumb(url: String, target: ImageView) {
        bitmapCache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { fetchBitmap(url, dp(104) * 2) } ?: return@launch
            bitmapCache.put(url, bmp)
            target.setImageBitmap(bmp)
        }
    }

    private fun fetchBitmap(url: String, targetWidth: Int): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "okhttp/4.12.0")
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > targetWidth && sample < 8) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun showFullImage(url: String) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        val dialog = AlertDialog.Builder(this).setView(iv).create()
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { fetchBitmap(url, 1600) }
            if (bmp != null) iv.setImageBitmap(bmp)
        }
    }

    // ============ 操作记录 ============

    /**
     * 远程操作记录：官方接口 `getControlActionHistory`（POST，最近一年），
     * 与 EasyEADO 的「操作记录」同源；失败时退回本机下发日志。
     */
    private fun loadRecords(force: Boolean) {
        val tip = tip("正在拉取操作记录…")
        content.addView(tip)
        val carId = TokenStore.carId
        if (carId.isEmpty()) {
            tip.text = "还没有选中车辆"
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    cached("records_$carId", force) { XiaoanApi.fetchControlActionHistory(carId) }
                }
            }
            content.removeView(tip)
            r.onSuccess { list ->
                if (list.isEmpty()) {
                    content.addView(tip("最近一年没有远程操作记录。"))
                    return@onSuccess
                }
                content.addView(tip("最近一年共 ${list.size} 条远控记录"))
                for (rec in list) content.addView(serverRow(rec))
            }.onFailure { e ->
                content.addView(tip("服务端拉取失败：${e.message}\n先显示本机记录。"))
                renderLocalLog()
            }
        }
    }

    private fun renderLocalLog() {
        val list = io.github.aw1y2z.changan.data.ControlLog.list(this)
        if (list.isEmpty()) {
            content.addView(tip("本机也没有下发记录。"))
            return
        }
        content.addView(sectionTitle("本机下发记录"))
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S", Locale.getDefault())
        for (e in list) content.addView(localRow(e, fmt))
    }

    private fun serverRow(rec: XiaoanApi.ActionRecord): View {
        val row = recordRow(
            ok = rec.ok,
            title = rec.label,
            subtitle = rec.sendTime.ifEmpty { rec.finishTime },
            status = rec.status,
            detail = rec.result,
        )
        return row
    }

    private fun localRow(
        e: io.github.aw1y2z.changan.data.ControlLog.Entry,
        fmt: SimpleDateFormat,
    ): View = recordRow(
        ok = e.ok,
        title = e.action,
        subtitle = fmt.format(java.util.Date(e.time)),
        status = if (e.ok) "成功" else "失败",
        detail = e.message,
    )

    private fun recordRow(
        ok: Boolean,
        title: String,
        subtitle: String,
        status: String,
        detail: String,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        row.layoutParams = lp

        row.addView(TextView(this).apply {
            text = if (ok) "✓" else "!"
            gravity = Gravity.CENTER
            setTextColor(getColor(if (ok) R.color.accent else R.color.danger))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT))

        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        middle.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        middle.addView(TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.txt_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, 0)
        })
        if (detail.isNotBlank()) {
            middle.addView(TextView(this).apply {
                text = detail
                setTextColor(getColor(R.color.txt_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(middle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = status
            setTextColor(getColor(if (ok) R.color.accent else R.color.danger))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        return row
    }

    // ============ 控车码管理 ============

    private fun loadPin() {
        val carId = TokenStore.carId
        val stateTip = tip("正在查询账号控车码状态…")
        content.addView(stateTip)

        scope.launch {
            val pinSet = withContext(Dispatchers.IO) {
                runCatching { XiaoanApi.fetchPinSwitch(carId) }.getOrDefault(false)
            }
            stateTip.text = if (pinSet) {
                "账号已在官方 App 设置过控车码。\n本机保存的控车码仅用于本 App 下发指令。"
            } else {
                "账号还没有控车码。\n请先在官方 App 中设置，再回到这里填写。"
            }

            content.addView(sectionTitle("本机设置"))
            content.addView(switchRow("控车码开关", TokenStore.controlPinEnabled) {
                TokenStore.controlPinEnabled = it
            })
            content.addView(switchRow("操作二次确认", TokenStore.controlPinConfirm) {
                TokenStore.controlPinConfirm = it
            })
            content.addView(pinInputRow())
        }
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        row.layoutParams = lp
        row.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        return row
    }

    private fun pinInputRow(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        card.addView(TextView(this).apply {
            text = "本机控车码"
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        card.addView(TextView(this).apply {
            text = "6 位数字，只保存在本机，用于下发控车指令；与官方 App 设置的控车码保持一致。"
            setTextColor(getColor(R.color.txt_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        })

        val input = EditText(this).apply {
            hint = "输入 6 位控车码"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(TokenStore.xiaoanControlPin)
            setTextColor(getColor(R.color.txt_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        card.addView(input)

        card.addView(TextView(this).apply {
            text = "保存"
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
            setTextColor(getColor(R.color.brand))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.length != 6 || !pin.all { it.isDigit() }) {
                    toast("请输入 6 位数字控车码")
                    return@setOnClickListener
                }
                TokenStore.xiaoanControlPin = pin
                input.setText("")
                toast("已保存到本机")
            }
        })
        return card
    }

    // ============ 小工具 ============

    private fun sectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.txt_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(4), dp(16), dp(4), dp(2))
    }

    private fun tip(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.txt_tertiary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(4), dp(6), dp(4), dp(10))
    }

    private fun space(width: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
