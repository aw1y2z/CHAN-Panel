package io.github.aw1y2z.changan.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.data.ChanganAuth
import io.github.aw1y2z.changan.data.InfoCenter
import io.github.aw1y2z.changan.data.InformationApi
import io.github.aw1y2z.changan.data.NotifyCenter
import io.github.aw1y2z.changan.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 信息中心：以**官方消息中心**为主（`/openc-apigw/fc/api/v2/open/information/`），
 * 拉到什么就显示什么；官方接口不可用时退回本机车况检测（[InfoCenter]）。
 *
 * 官方消息按 `infoTypeFullPath` 分组：
 *  - APP-CONTROL-* → 点火熄火
 *  - APP-GUARD-*   → 智能守护
 *  - 其余          → 其他
 */
class InfoCenterActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout

    /** "" = 全部；"control" = 点火熄火；"security" = 安全守护 */
    private var msgType: String = ""

    private val official = ArrayList<InformationApi.Msg>()
    private var total = 0
    private var loading = false
    private var officialFailed = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info_center)
        msgType = intent.getStringExtra("type").orEmpty()
        content = findViewById(R.id.content)

        findViewById<TextView>(R.id.tvTitle).text = when (msgType) {
            "control" -> "点火熄火"
            "security" -> "安全守护"
            else -> "消息中心"
        }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnClear).apply {
            text = "全部已读"
            setOnClickListener { markAllRead() }
            // 长按发一条测试通知，用来确认通知权限和渠道是否正常
            setOnLongClickListener {
                NotifyCenter.pushTest(applicationContext)
                android.widget.Toast.makeText(this@InfoCenterActivity, "已发送测试通知", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }

        scope.launch {
            // 先把本机消息落一次「看过」，避免旧角标残留
            withContext(Dispatchers.IO) { runCatching { InfoCenter.markAllRead(applicationContext) } }
            loadNextPage()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============ 数据 ============

    private fun loadNextPage() {
        if (loading) return
        loading = true
        scope.launch {
            // 服务端固定每页 200 条、不吃 displayLength，所以偏移用「已加载条数」而不是页码。
            // 手表 1.1 的消息中心是两个数据源（APP-CONTROL / APP-SECURITY），首屏分别拉一次后合并。
            val offset = official.size
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (msgType) {
                        "control" -> InformationApi.list(offset, PAGE_SIZE, InformationApi.TYPE_CONTROL)
                        "security" -> InformationApi.list(
                            0,
                            PAGE_SIZE,
                            InformationApi.TYPE_SECURITY,
                            trackUserId = securityUserId(),
                        )
                        else -> {
                            val control = InformationApi.list(offset, PAGE_SIZE, InformationApi.TYPE_CONTROL)
                            if (offset == 0) {
                                val security = InformationApi.list(
                                    0,
                                    PAGE_SIZE,
                                    InformationApi.TYPE_SECURITY,
                                    trackUserId = securityUserId(),
                                )
                                InformationApi.Page(
                                    control.total + security.total,
                                    control.items + security.items,
                                )
                            } else {
                                control
                            }
                        }
                    }
                }
            }
            r.onSuccess {
                // 去重，避免服务端忽略偏移时插进重复项
                val known = official.mapTo(HashSet()) { it.id }
                official += it.items.filter { m -> !known.contains(m.id) }
                total = it.total
                officialFailed = false
            }.onFailure {
                officialFailed = true
            }
            loading = false
            render()
        }
    }

    /** 安全守护的 trackUserId：getUserInfo 返回的 userId，取不到再兜底 uid */
    private fun securityUserId(): String {
        TokenStore.officialUserId.takeIf { it.isNotEmpty() }?.let { return it }
        val fetched = runCatching { ChanganAuth.fetchOfficialUserId() }.getOrDefault("")
        return fetched.ifEmpty { TokenStore.userId }
    }

    private fun markAllRead() {
        val unread = official.filter { !it.read }
        if (unread.isEmpty()) {
            toastTip("没有未读消息")
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { InformationApi.markRead(unread) }
            if (ok) {
                for (i in official.indices) {
                    official[i] = official[i].copy(read = true)
                }
                render()
            } else {
                toastTip("标记失败，请稍后再试")
            }
        }
    }

    private fun markOneRead(m: InformationApi.Msg) {
        if (m.read) return
        val idx = official.indexOfFirst { it.id == m.id }
        if (idx < 0) return
        official[idx] = official[idx].copy(read = true)
        render()
        scope.launch {
            withContext(Dispatchers.IO) { InformationApi.markRead(listOf(m)) }
        }
    }

    // ============ 渲染 ============

    private fun render() {
        content.removeAllViews()

        if (official.isEmpty()) {
            if (officialFailed) {
                content.addView(tipView("官方消息拉取失败，下面显示本机检测记录。"))
                renderLocal()
            } else {
                content.addView(
                    tipView("还没有官方消息。\n车辆点火、熄火、智能守护触发后，这里会同步官方推送。"),
                )
                renderLocal()
            }
            return
        }

        val unread = official.count { !it.read }
        content.addView(
            tipView("共 $total 条，已加载 ${official.size} 条" + if (unread > 0) "，未读 $unread" else ""),
        )

        // 与手表 1.1 的两个数据源对齐：点熄火 = APP-CONTROL，安全守护 = APP-SECURITY
        when (msgType) {
            "control" -> addGroup("点火熄火", official)
            "security" -> addGroup("安全守护", official)
            else -> {
                addGroup("点火熄火", official.filter { it.typePath.startsWith(InformationApi.TYPE_CONTROL) })
                addGroup("安全守护", official.filter { it.typePath.startsWith(InformationApi.TYPE_SECURITY) })
                addGroup("其他", official.filter {
                    !it.typePath.startsWith(InformationApi.TYPE_CONTROL) &&
                        !it.typePath.startsWith(InformationApi.TYPE_SECURITY)
                })
            }
        }

        if (official.size < total) {
            content.addView(loadMoreView())
        }
        renderLocal()
    }

    /**
     * 本机车况检测的「安全守护」。
     * 官方接口只有点火熄火和智能守护，未锁车/车窗/胎压这些只能靠本地检测补。
     */
    private fun renderLocal() {
        val local = runCatching { InfoCenter.messages(applicationContext) }.getOrDefault(emptyList())
        val safety = local.filter { it.kind == InfoCenter.KIND_SAFETY }
        if (safety.isEmpty()) return
        addLocalGroup("本机安全守护", safety)
    }

    private fun addGroup(title: String, list: List<InformationApi.Msg>) {
        if (list.isEmpty()) return
        content.addView(sectionTitle("$title · ${list.size}"))
        val inflater = LayoutInflater.from(this)
        for (m in list) {
            val row = inflater.inflate(R.layout.item_info_message, content, false)
            row.findViewById<View>(R.id.dotUnread).visibility =
                if (m.read) View.INVISIBLE else View.VISIBLE
            row.findViewById<TextView>(R.id.tvTitle).text =
                if (m.typeName.isBlank()) m.title else m.typeName
            row.findViewById<TextView>(R.id.tvTime).text = timeText(parseTime(m.createdAt))
            val detail = row.findViewById<TextView>(R.id.tvDetail)
            detail.text = m.content
            detail.visibility = if (m.content.isBlank()) View.GONE else View.VISIBLE
            row.setOnClickListener { markOneRead(m) }
            content.addView(row)
        }
    }

    private fun addLocalGroup(title: String, list: List<InfoCenter.Message>) {
        if (list.isEmpty()) return
        content.addView(sectionTitle("$title · ${list.size}"))
        val inflater = LayoutInflater.from(this)
        for (m in list) {
            val row = inflater.inflate(R.layout.item_info_message, content, false)
            row.findViewById<View>(R.id.dotUnread).visibility =
                if (m.read) View.INVISIBLE else View.VISIBLE
            row.findViewById<TextView>(R.id.tvTitle).text = m.title
            row.findViewById<TextView>(R.id.tvTime).text = timeText(m.time)
            val detail = row.findViewById<TextView>(R.id.tvDetail)
            detail.text = m.detail
            detail.visibility = if (m.detail.isBlank()) View.GONE else View.VISIBLE
            content.addView(row)
        }
    }

    private fun loadMoreView(): TextView = TextView(this).apply {
        text = "加载更多"
        gravity = android.view.Gravity.CENTER
        setTextColor(getColor(R.color.accent))
        textSize = 13f
        setPadding(dp(4), dp(16), dp(4), dp(16))
        setOnClickListener {
            isEnabled = false
            text = "加载中…"
            loadNextPage()
        }
    }

    private fun toastTip(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun sectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.txt_secondary))
        textSize = 11f
        letterSpacing = 0.06f
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun tipView(label: String, small: Boolean = false): TextView = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.txt_tertiary))
        textSize = if (small) 12f else 12.5f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(4), dp(4), dp(4), dp(10))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun parseTime(text: String): Long =
        runCatching { timeFmt.parse(text)?.time ?: 0L }.getOrDefault(0L)

    private fun timeText(time: Long): String {
        if (time <= 0L) return ""
        val diff = System.currentTimeMillis() - time
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
