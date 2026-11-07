package com.zmw.autocheckin

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 统一的工作日判定：自定义不打卡日 → 法定节假日 → 调休补班 → 周几掩码。
 *
 * 判定优先级（从高到低）：
 * 1. 自定义不打卡日期（用户手动指定，最高优先，直接判定为非工作日）
 * 2. 法定节假日（模式 1/2 下跳过）
 * 3. 调休补班日（模式 2 下强制为工作日）
 * 4. 周几掩码（原有逻辑）
 *
 * 节假日数据可来自在线接口缓存或内置兜底表，见 [HolidayRepo]。
 * 判定本身走内存缓存，无 IO、无网络，微秒级。
 */
object WorkDay {

    fun key(c: Calendar): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(c.time)

    fun keyOf(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestamp))

    fun dayBit(c: Calendar): Int = when (c.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6
    }

    fun isWorkDay(ctx: Context, c: Calendar): Boolean {
        val k = key(c)

        // 1. 自定义不打卡日优先级最高
        if (Prefs.customOffDays(ctx).contains(k)) return false

        val mode = Prefs.holidayMode(ctx)
        if (mode != Prefs.MODE_IGNORE) {
            // 2. 法定节假日：跳过
            if (HolidayRepo.holidayNameOf(ctx, k) != null) return false
            // 3. 调休补班日：即便落在他没勾选的周六/周日，也照常打卡
            if (mode == Prefs.MODE_OFF_HOLIDAY_WITH_MAKEUP && HolidayRepo.makeupNameOf(ctx, k) != null) return true
        }

        // 4. 原有周几掩码
        return (Prefs.days(ctx) and (1 shl dayBit(c))) != 0
    }

    fun isWorkDay(ctx: Context, timestamp: Long): Boolean =
        isWorkDay(ctx, Calendar.getInstance().apply { timeInMillis = timestamp })

    /**
     * 今日状态描述，用于首页展示与日志。
     * 例：「春节（法定节假日）· 不打卡」「调休补班日 · 照常打卡」「工作日」。
     */
    fun describe(ctx: Context, c: Calendar): String {
        val k = key(c)
        if (Prefs.customOffDays(ctx).contains(k)) return "自定义不打卡日"

        val mode = Prefs.holidayMode(ctx)
        var holiday: String? = null
        var makeup: String? = null
        if (mode != Prefs.MODE_IGNORE) {
            holiday = HolidayRepo.holidayNameOf(ctx, k)
            if (mode == Prefs.MODE_OFF_HOLIDAY_WITH_MAKEUP) makeup = HolidayRepo.makeupNameOf(ctx, k)
        }

        if (holiday != null) return "${holiday}（法定节假日）· 不打卡"
        if (makeup != null) return "调休补班日 · 照常打卡"

        val inMask = (Prefs.days(ctx) and (1 shl dayBit(c))) != 0
        val base = if (inMask) "工作日" else "非生效日（周几未勾选）"

        // 当年无任何数据时明确提示：否则用户会以为节假日规则已生效其实没有
        val year = c.get(Calendar.YEAR)
        if (mode != Prefs.MODE_IGNORE && !HolidayRepo.hasData(ctx, year)) {
            return "$base · $year 年节假日无数据（可到「节假日数据」里刷新）"
        }
        return base
    }
}
