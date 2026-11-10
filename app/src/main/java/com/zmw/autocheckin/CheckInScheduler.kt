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
    const val ACTION_RESUME = "com.zmw.autocheckin.action.RESUME"
    const val ACTION_STATUS_DUE = "com.zmw.autocheckin.action.STATUS_DUE"
    private const val REQ = 1001
    private const val REQ_RESUME = 1002
    private const val REQ_STATUS = 1003

    /** 锁屏待办的唤醒检查步距。RTC 不唤醒设备：休眠期零耗电，唤醒瞬间立即送达 */
    const val RESUME_STEP_MS = 20_000L

    /** 一次排定的触发时刻 + 时段，写入 Prefs 供顺延送达时还原 */
    data class Trigger(val at: Long, val slot: String)

    /** 重排下一次闹钟；每次触发后都会先重排，避免异常导致停摆 */
    fun schedule(ctx: Context) {
        // 状态推送是独立的用户级开关，不受「启用定时任务」影响，
        // 所以无论主闹钟是否启用，都在这里同步重排一次（幂等）。
        scheduleStatus(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)

        if (!Prefs.enabled(ctx)) {
            Prefs.log(ctx, "定时任务已关闭")
            return
        }

        val trigger = nextTrigger(ctx)
        if (trigger == null) {
            Prefs.clearNextDue(ctx)
            Prefs.log(ctx, "没有可用的触发时间")
            return
        }
        val at = trigger.at
        Prefs.setNextDue(ctx, at, trigger.slot)

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

    /**
     * 锁屏待办的补执行检查。用 RTC（非唤醒）：设备休眠时闹钟不会响、不耗电，
     * 用户一开屏设备唤醒，到期闹钟立刻送达 —— 这就是「开屏后自动补打卡」的可靠入口，
     * 不依赖可能被系统限制或被省电策略杀掉的 ACTION_USER_PRESENT 广播。
     */
    fun scheduleResume(ctx: Context, delayMs: Long = RESUME_STEP_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = resumePending(ctx)
        am.cancel(pi)
        if (!Prefs.hasPending(ctx)) return
        val at = System.currentTimeMillis() + delayMs
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExact(AlarmManager.RTC, at, pi) else am.set(AlarmManager.RTC, at, pi)
    }

    /**
     * 每日固定时间的状态推送闹钟。同样用 RTC 非唤醒：一天一次，
     * 设备休眠时不吵醒 CPU、顺延到唤醒后送达，几乎不增加耗电。
     */
    fun scheduleStatus(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = statusPending(ctx)
        am.cancel(pi)
        if (!Prefs.statusPushEnabled(ctx)) return

        val m = Prefs.statusPushMinutes(ctx)
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.let { c ->
            if (c.timeInMillis <= System.currentTimeMillis() + 30_000L) {
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
            c.timeInMillis
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExact(AlarmManager.RTC, at, pi) else am.set(AlarmManager.RTC, at, pi)
        val source = if (Prefs.statusUseApi(ctx)) "企业微信接口" else "本机记录"
        Prefs.log(ctx, "下次状态推送 " + fmt(at) + "（数据源：" + source + "）")
    }

    fun cancelStatus(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(statusPending(ctx))
    }

    private fun statusPending(ctx: Context): PendingIntent {
        val i = Intent(ctx, StatusPushReceiver::class.java).setAction(ACTION_STATUS_DUE)
        return PendingIntent.getBroadcast(
            ctx, REQ_STATUS, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelResume(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(resumePending(ctx))
    }

    private fun resumePending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ResumeReceiver::class.java).setAction(ACTION_RESUME)
        return PendingIntent.getBroadcast(
            ctx, REQ_RESUME, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
     * 仍找不到（例如一个周几都没勾）返回 null，由 schedule() 记日志。
     */
    private fun nextTrigger(ctx: Context): Trigger? {
        val up = Prefs.upMinutes(ctx)
        val down = Prefs.downMinutes(ctx)
        val slots = intArrayOf(up, down)
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
                if (t.timeInMillis > now + 30_000L) {
                    return Trigger(t.timeInMillis, SlotResolver.slotForMinute(ctx, m))
                }
            }
        }
        return null
    }

    fun dayBit(c: Calendar): Int = WorkDay.dayBit(c)

    private fun fmt(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
}
