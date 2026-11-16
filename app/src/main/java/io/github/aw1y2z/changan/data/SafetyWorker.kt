package io.github.aw1y2z.changan.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台巡检，两条通道都走一遍：
 *
 * 1. **官方消息**（[SafetyCheck]）：车端上报的疏忽提醒，如「车门未闭锁」
 * 2. **本机车况检测**（[InfoCenter]）：补官方不发的那几类（车窗、胎压、电瓶、钥匙电量）
 *
 * 由 [io.github.aw1y2z.changan.App] 注册成 15 分钟一次的唯一周期任务。
 */
class SafetyWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (TokenStore.accessToken.isEmpty() || TokenStore.carId.isEmpty()) {
            return Result.success()
        }

        // 1. 官方消息：新的未读推通知
        runCatching { SafetyCheck.pushNewOfficial(ctx) }

        // 2. 本地车况检测：安全守护兜底
        val status = withContext(Dispatchers.IO) { ControlActions.fetchStatus().getOrNull() }
        if (status != null) {
            val added = runCatching { InfoCenter.observe(ctx, status) }.getOrDefault(emptyList())
            NotifyCenter.push(ctx, added)
        }
        return Result.success()
    }
}
