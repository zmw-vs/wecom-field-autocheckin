package com.zmw.autocheckin

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 节假日数据仓库。数据来源优先级：
 *   1. 在线接口拉取并缓存在本地的年度数据（主数据源，新年份自动覆盖）；
 *   2. 内置兜底表（仅当网络不可用 / 接口尚未发布该年安排时使用，保证不漏卡）；
 *   3. 都没有 → 该年按周几规则执行，界面提示。
 *
 * 性能：查询走内存缓存，同一年只加载一次；只有 App 启动 / 定时任务 / 手动刷新时
 * 才可能触发一次网络请求（每年几次，几 KB）。判定路径零 IO、零网络。
 */
object HolidayRepo {

    /** off=true 表示放假（不用打卡），false 表示调休补班（要打卡） */
    data class DayInfo(val off: Boolean, val name: String)

    private val FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    // ---------------- 内置兜底表 ----------------
    // 只作为网络不可用时的安全网，不参与日常判定优先级。
    // 数据来源：国务院办公厅 2025-11-04（2026）、2026-11-05（2027）通知。
    private val fbOff = HashMap<String, String>(128)
    private val fbWork = HashMap<String, String>(32)

    // ---------------- 内存缓存 ----------------
    private val lock = Any()
    private var memYear = -1
    private var memMap: Map<String, DayInfo> = emptyMap()
    private var memSource = ""

    init {
        // 2026
        span("20260101", "20260103", "元旦")
        span("20260215", "20260223", "春节")
        span("20260404", "20260406", "清明")
        span("20260501", "20260505", "劳动节")
        span("20260619", "20260621", "端午")
        span("20260925", "20260927", "中秋")
        span("20261001", "20261007", "国庆")
        makeup("20260104", "元旦调休")
        makeup("20260214", "春节调休")
        makeup("20260228", "春节调休")
        makeup("20260509", "劳动节调休")
        makeup("20260920", "国庆调休")
        makeup("20261010", "国庆调休")

        // 2027
        span("20270101", "20270103", "元旦")
        span("20270205", "20270213", "春节")
        span("20270403", "20270405", "清明")
        span("20270501", "20270505", "劳动节")
        span("20270609", "20270609", "端午")
        span("20270915", "20270915", "中秋")
        span("20271001", "20271007", "国庆")
        makeup("20270131", "春节调休")
        makeup("20270214", "春节调休")
        makeup("20270508", "劳动节调休")
        makeup("20270926", "国庆调休")
        makeup("20271009", "国庆调休")
    }

    private fun span(start: String, end: String, name: String) {
        var d = LocalDate.parse(start, FMT)
        val e = LocalDate.parse(end, FMT)
        while (!d.isAfter(e)) {
            fbOff[d.format(FMT)] = name
            d = d.plusDays(1)
        }
    }

    private fun makeup(date: String, name: String) {
        fbWork[date] = name
    }

    // ---------------- 查询 ----------------

    /**
     * 取某年数据。在线缓存 > 内置兜底 > 空。
     * 结果进内存缓存，后续调用直接命中。
     */
    fun dataFor(ctx: Context, year: Int): Map<String, DayInfo> {
        synchronized(lock) {
            if (year == memYear && memSource.isNotEmpty()) return memMap

            val map = HashMap<String, DayInfo>(80)
            // 1. 在线缓存
            for (entry in Prefs.holidayCache(ctx, year)) {
                val parts = entry.split('|')
                if (parts.size >= 3 && parts[0].length == 8) {
                    map[parts[0]] = DayInfo(parts[1] == "1", parts[2])
                }
            }
            val src = if (map.isNotEmpty()) {
                "在线数据（" + Prefs.holidayCacheTimeText(ctx, year) + "）"
            } else {
                // 2. 内置兜底
                val prefix = year.toString()
                for ((k, v) in fbOff) if (k.startsWith(prefix)) map[k] = DayInfo(true, v)
                for ((k, v) in fbWork) if (k.startsWith(prefix)) map[k] = DayInfo(false, v)
                if (map.isNotEmpty()) "内置兜底表" else "无数据"
            }

            memYear = year
            memMap = map
            memSource = src
            return map
        }
    }

    fun holidayNameOf(ctx: Context, dateKey: String): String? {
        val year = dateKey.take(4).toIntOrNull() ?: return null
        val d = dataFor(ctx, year)[dateKey] ?: return null
        return if (d.off) d.name else null
    }

    fun makeupNameOf(ctx: Context, dateKey: String): String? {
        val year = dateKey.take(4).toIntOrNull() ?: return null
        val d = dataFor(ctx, year)[dateKey] ?: return null
        return if (!d.off) d.name else null
    }

    /** 当前年份的数据来源说明，供界面展示 */
    fun sourceText(ctx: Context, year: Int): String {
        dataFor(ctx, year)
        synchronized(lock) {
            return if (memYear == year) memSource else "未加载"
        }
    }

    /** 该年是否有任何可用数据（在线或兜底） */
    fun hasData(ctx: Context, year: Int): Boolean = dataFor(ctx, year).isNotEmpty()

    /** 有在线数据的年份列表，用于界面展示已缓存情况 */
    fun cachedYears(ctx: Context): List<Int> = Prefs.holidayCachedYears(ctx).sorted()

    // ---------------- 刷新 ----------------

    /**
     * 异步拉取并写缓存。callback 在主线程回调（ok, 提示文案）。
     * 不阻塞任何主流程：闹钟触发时调用也不会影响打卡。
     */
    fun refreshAsync(ctx: Context, year: Int, callback: ((Boolean, String) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val data = HolidayApi.fetch(app, year)
            val ok: Boolean
            val msg: String
            if (data.isNullOrEmpty()) {
                ok = false
                msg = "$year 年：接口无数据或网络不通，继续使用" +
                    (if (hasData(app, year)) "内置兜底表" else "周几规则")
            } else {
                Prefs.saveHolidayCache(app, year, data)
                invalidate()
                ok = true
                msg = "$year 年：已更新 ${data.size} 条（" +
                    "放假 ${data.values.count { it.off }} 天，" +
                    "调休 ${data.values.count { !it.off }} 天）"
            }
            Prefs.log(app, "节假日刷新 → $msg")
            if (callback != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(ok, msg) }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 只在「当年还没有在线数据」时静默拉一次，用于 App 启动与每日调度。
     * 有缓存就完全不发请求 —— 一年最多几次。
     */
    fun ensureYearAsync(ctx: Context, year: Int, callback: ((Boolean, String) -> Unit)? = null) {
        if (Prefs.holidayCache(ctx, year).isNotEmpty()) return
        refreshAsync(ctx, year, callback)
    }

    fun invalidate() {
        synchronized(lock) {
            memYear = -1
            memMap = emptyMap()
            memSource = ""
        }
    }
}
