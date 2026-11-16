package io.github.aw1y2z.changan

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.aw1y2z.changan.data.NotifyCenter
import io.github.aw1y2z.changan.data.SafetyWorker
import io.github.aw1y2z.changan.data.TokenStore
import java.util.concurrent.TimeUnit

/**
 * 进程起点：先初始化 [TokenStore]，再按用户选择定好夜间模式。
 *
 * 夜间模式必须在这里定，晚于 Activity 创建的话会先按浅色画一帧再重建，
 * 能看到明显的闪白。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        TokenStore.init(this)
        AppCompatDelegate.setDefaultNightMode(delegateModeOf(TokenStore.nightMode))

        NotifyCenter.ensureChannels(this)
        scheduleSafetyCheck()
    }

    /**
     * 15 分钟一次的后台巡检（WorkManager 的最小周期）。
     * 唯一任务 + KEEP：重复启动不会叠加多个任务。
     */
    private fun scheduleSafetyCheck() {
        val req = PeriodicWorkRequestBuilder<SafetyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "safety-check",
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }

    companion object {
        /** 本机存的值（0/1/2）→ AppCompatDelegate 的夜间模式常量 */
        fun delegateModeOf(value: Int): Int = when (value) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
