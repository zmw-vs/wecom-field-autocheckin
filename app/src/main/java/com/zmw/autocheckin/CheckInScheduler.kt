package com.zmw.autocheckin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CheckInScheduler {

    const val ACTION_DUE = "com.zmw.autocheckin.action.DUE"
    private const val REQ = 1001

    /** 重排下一次闹钟；每次触发后都会先重排，避免异常导致停摆 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)

        if (!Prefs.enabled(ctx)) {
            Prefs.log(ctx, "定时任务已关闭")
            return
        }

        val at = nextTriggerAt(ctx)
        if (at <= 0L) {
            Prefs.log(ctx, "没有可用的触发时间")
            return
        }

        // 每年只做一次：当年节假日数据缺失时后台拉一次，不阻塞调度，
        // 也不会在已有缓存时产生任何网络请求。
        HolidayRepo.ensureYearAsync(ctx, Calendar.getInstance().get(Calendar.YEAR))

        // 省电关键：RTC（非唤醒）而非 RTC_WAKEUP + allowWhileIdle。
        // 设备休眠时不吵醒 CPU，闹钟顺延到设备唤醒时补触发；
        // 锁屏场景本就只能发通知，由「解锁续跑」路径兜底，功能无损。
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExact(AlarmManager.RTC, at, pi)
        } else {
            am.set(AlarmManager.RTC, at, pi)
        }
        Prefs.log(ctx, "下次触发 " + fmt(at) + "（休眠时顺延至唤醒，不唤醒设备）· 今日 " +
                WorkDay.describe(ctx, Calendar.getInstance()))
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DUE)
        return PendingIntent.getBroadcast(
            ctx, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Bug-4 修复：以同一个基准 Calendar clone 后偏移（避免循环内反复 getInstance+add
     * 在月末/年末产生日历回滚错乱）。
     *
     * 节假日支持后搜索窗口由 14 天扩到 60 天：跳过春节 9 天连休 + 前后周末后，
     * 14 天窗口可能全部落空导致闹钟不再排定（真正的断档）。60 天纯内存循环
     * 120 次，开销可忽略，且能覆盖任何国内假期。
     * 仍找不到（例如一个周几都没勾）返回 -1，由 schedule() 记日志。
     */
    private fun nextTriggerAt(ctx: Context): Long {
        val slots = intArrayOf(Prefs.upMinutes(ctx), Prefs.downMinutes(ctx))
        val now = System.currentTimeMillis()
        val base = Calendar.getInstance()

        for (offset in 0..60) {
            val day = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            // 工作日判定统一收口到 WorkDay：周几掩码 + 法定节假日 + 调休补班 + 自定义不打卡日
            if (!WorkDay.isWorkDay(ctx, day)) continue

            for (m in slots) {
                val t = (base.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                    set(Calendar.HOUR_OF_DAY, m / 60)
                    set(Calendar.MINUTE, m % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (t.timeInMillis > now + 30_000L) return t.timeInMillis
            }
        }
        return -1L
    }

    fun dayBit(c: Calendar): Int = WorkDay.dayBit(c)

    private fun fmt(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
}
