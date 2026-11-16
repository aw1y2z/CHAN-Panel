package io.github.aw1y2z.changan.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安全守护的统一入口：拉官方消息，把本机没推过的未读消息推成通知。
 *
 * 两个调用点：
 *  - [SafetyWorker]：后台每 15 分钟一次
 *  - `DashboardActivity`：用户下拉刷新车况时顺手跑一次，前台也能立刻收到
 *
 * 官方「智能守护」（`APP-GUARD-SMART_GUARD`）就是车端上报的疏忽提醒，
 * 例如「车门未闭锁」，这是本地轮询推断不出来的，所以必须走官方通道。
 */
object SafetyCheck {

    /** 一次最多推几条，避免积压很久后一开 App 炸出十几条 */
    private const val MAX_PUSH_PER_RUN = 3

    /** 调试开关：忽略已读状态，直接推最新一条守护消息，用来验证通知链路（正式版关掉） */
    private const val DEBUG_PUSH_LATEST_GUARD = false

    /**
     * 拉最新一页官方消息，推送新的未读。
     * @return 本次推送出去的条数
     */
    suspend fun pushNewOfficial(ctx: Context): Int {
        if (TokenStore.accessToken.isEmpty() || TokenStore.userId.isEmpty()) return 0

        val page = withContext(Dispatchers.IO) {
            runCatching { InformationApi.list(0, 200) }.getOrNull()
        } ?: return 0

        val fresh = if (DEBUG_PUSH_LATEST_GUARD) {
            page.items.filter { it.typePath.contains("GUARD") }.take(1)
        } else {
            page.items
                .filter { !it.read }
                .filter { PushState.markPushed(ctx, it.id) }
        }
        if (fresh.isEmpty()) return 0

        val picked = fresh.take(MAX_PUSH_PER_RUN)
        NotifyCenter.pushOfficial(ctx, picked)
        return picked.size
    }
}
