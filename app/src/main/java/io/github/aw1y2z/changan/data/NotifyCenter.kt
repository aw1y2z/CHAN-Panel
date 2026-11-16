package io.github.aw1y2z.changan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.aw1y2z.changan.R
import io.github.aw1y2z.changan.ui.InfoCenterActivity

/**
 * 系统通知：把 [InfoCenter] 产出的安全守护 / 点熄火消息推成通知栏。
 *
 * 两个渠道分开，方便用户单独关掉点熄火的提醒：
 *  - 安全守护：高优先级，带震动（未锁车、车窗未关、胎压异常…）
 *  - 点熄火：默认优先级
 */
object NotifyCenter {

    const val CH_SAFETY = "safety"
    const val CH_IGNITION = "ignition"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(CH_SAFETY) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_SAFETY, "安全守护", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "未锁车、车窗未关、胎压异常、电瓶亏电等提醒"
                    enableVibration(true)
                },
            )
        }
        if (nm.getNotificationChannel(CH_IGNITION) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_IGNITION, "点熄火", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "车辆启动、熄火提醒"
                },
            )
        }
    }

    /** 把一批新消息推成通知；没有通知权限时静默失败 */
    fun push(ctx: Context, msgs: List<InfoCenter.Message>) {
        if (msgs.isEmpty()) return
        ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        val intent = Intent(ctx, InfoCenterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        for (m in msgs) {
            val safety = m.kind == InfoCenter.KIND_SAFETY
            val pi = PendingIntent.getActivity(
                ctx,
                m.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, if (safety) CH_SAFETY else CH_IGNITION)
                .setSmallIcon(R.drawable.ic_c_bell)
                .setContentTitle(m.title)
                .setContentText(m.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(m.detail))
                .setPriority(
                    if (safety) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
                )
                .setCategory(
                    if (safety) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS,
                )
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(m.id.toInt(), n) }
        }
    }

    /**
     * 推官方消息（`APP-GUARD-*` 归到安全守护渠道，其余归点熄火渠道）。
     * 官方这类消息是车端主动上报的，比如「车门未闭锁」，比本地轮询更准。
     */
    fun pushOfficial(ctx: Context, msgs: List<InformationApi.Msg>) {
        if (msgs.isEmpty()) return
        push(
            ctx,
            msgs.map { info ->
                val guard = info.typePath.contains("GUARD")
                InfoCenter.Message(
                    // 通知 id 只要求进程内唯一，用 hashCode 取正数
                    id = (info.id.hashCode().toLong() and 0x7fffffff),
                    kind = if (guard) InfoCenter.KIND_SAFETY else InfoCenter.KIND_IGNITION,
                    title = if (info.typeName.isBlank()) info.title else info.typeName,
                    detail = if (info.content.isBlank()) info.summary else info.content,
                    time = System.currentTimeMillis(),
                    read = false,
                )
            },
        )
    }

    /** 调试用：立刻推一条，不依赖车况 */
    fun pushTest(ctx: Context) {
        push(
            ctx,
            listOf(
                InfoCenter.Message(
                    id = System.currentTimeMillis() % 100000,
                    kind = InfoCenter.KIND_SAFETY,
                    title = "车辆未上锁",
                    detail = "这是一条测试通知，确认通道正常可达。",
                    time = System.currentTimeMillis(),
                    read = false,
                ),
            ),
        )
    }
}
