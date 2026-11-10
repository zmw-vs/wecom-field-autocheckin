package com.zmw.autocheckin

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置与运行状态统一走 SharedPreferences：
 * 无障碍服务和前台 Activity 处于同一进程，不涉及跨进程共享问题。
 */
object Prefs {

    private const val FILE = "autocheckin"
    private const val K_ENABLED = "enabled"
    private const val K_UP = "up_minutes"
    private const val K_DOWN = "down_minutes"
    private const val K_DAYS = "weekday_mask"
    private const val K_HOLIDAY_MODE = "holiday_mode"
    private const val K_CUSTOM_OFF = "custom_off_days"
    private const val K_HOLIDAY_API = "holiday_api"
    private const val P_HOLIDAY_CACHE = "holiday_cache_"
    private const val P_HOLIDAY_CACHE_AT = "holiday_cache_at_"
    private const val K_AUTO = "auto_click"
    private const val K_PENDING = "pending"
    private const val K_PENDING_SLOT = "pending_slot"
    private const val K_PENDING_AT = "pending_at"
    private const val K_NEXT_DUE_AT = "next_due_at"
    private const val K_NEXT_DUE_SLOT = "next_due_slot"
    private const val K_SUCCESS_NOTIFIED = "success_notified"
    private const val K_STATUS_ENABLED = "status_push_enabled"
    private const val K_STATUS_MINUTES = "status_push_minutes"
    private const val K_STATUS_API = "status_api_url"
    private const val K_STATUS_TOKEN = "status_api_token"
    private const val K_STATUS_USER = "status_user_id"
    private const val K_STATUS_LAST_DATE = "status_last_date"
    private const val K_STATUS_LAST_RESULT = "status_last_result"
    private const val K_DONE_UP = "done_up_date"
    private const val K_DONE_DOWN = "done_down_date"
    private const val K_LOGS = "logs"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ENABLED, v).apply()

    fun upMinutes(ctx: Context): Int = sp(ctx).getInt(K_UP, 9 * 60)
    fun downMinutes(ctx: Context): Int = sp(ctx).getInt(K_DOWN, 18 * 60)
    fun setUp(ctx: Context, m: Int) = sp(ctx).edit().putInt(K_UP, m).apply()
    fun setDown(ctx: Context, m: Int) = sp(ctx).edit().putInt(K_DOWN, m).apply()

    /** bit0=周一 … bit6=周日，默认周一到周五 */
    fun days(ctx: Context): Int = sp(ctx).getInt(K_DAYS, 0b0011111)
    fun setDays(ctx: Context, mask: Int) = sp(ctx).edit().putInt(K_DAYS, mask).apply()

    /**
     * 节假日处理模式：
     * 0 = 忽略节假日（只看周几）
     * 1 = 法定节假日不打卡（默认，最贴合外勤场景）
     * 2 = 法定节假日不打卡 + 调休补班日照常打卡
     */
    fun holidayMode(ctx: Context): Int = sp(ctx).getInt(K_HOLIDAY_MODE, MODE_OFF_HOLIDAY)
    fun setHolidayMode(ctx: Context, m: Int) = sp(ctx).edit().putInt(K_HOLIDAY_MODE, m).apply()

    const val MODE_IGNORE = 0
    const val MODE_OFF_HOLIDAY = 1
    const val MODE_OFF_HOLIDAY_WITH_MAKEUP = 2

    /**
     * 自定义不打卡日期（yyyyMMdd）。用于公司额外放假、年假、请假等内置表覆盖不到的情况。
     * 优先级最高：命中即判定为非工作日，无视周几与节假日模式。
     */
    fun customOffDays(ctx: Context): Set<String> {
        // getStringSet 返回的集合不可修改，取副本避免调用方误改
        val raw = sp(ctx).getStringSet(K_CUSTOM_OFF, null) ?: return emptySet()
        return HashSet(raw)
    }

    fun addOffDay(ctx: Context, dateKey: String) {
        // customOffDays 声明为只读 Set，这里显式复制成 HashSet 再改
        val next = HashSet(customOffDays(ctx))
        next.add(dateKey)
        sp(ctx).edit().putStringSet(K_CUSTOM_OFF, next).apply()
    }

    fun removeOffDay(ctx: Context, dateKey: String) {
        val next = HashSet(customOffDays(ctx))
        next.remove(dateKey)
        sp(ctx).edit().putStringSet(K_CUSTOM_OFF, next).apply()
    }

    // ---------------- 节假日在线数据 ----------------

    /** 默认接口：不带年份返回当年，拼上年份返回指定年 */
    const val DEFAULT_HOLIDAY_API = "https://timor.tech/api/holiday/year"

    fun holidayApi(ctx: Context): String =
        sp(ctx).getString(K_HOLIDAY_API, DEFAULT_HOLIDAY_API)?.ifBlank { DEFAULT_HOLIDAY_API }
            ?: DEFAULT_HOLIDAY_API

    fun setHolidayApi(ctx: Context, url: String) =
        sp(ctx).edit().putString(K_HOLIDAY_API, url.trim()).apply()

    fun resetHolidayApi(ctx: Context) = sp(ctx).edit().remove(K_HOLIDAY_API).apply()

    /** 缓存条目格式："yyyyMMdd|1或0|节名"，1=放假 0=调休补班 */
    fun holidayCache(ctx: Context, year: Int): Set<String> {
        val raw = sp(ctx).getStringSet(P_HOLIDAY_CACHE + year, null) ?: return emptySet()
        return HashSet(raw)
    }

    fun saveHolidayCache(ctx: Context, year: Int, data: Map<String, HolidayRepo.DayInfo>) {
        val set = HashSet<String>(data.size * 2)
        for ((date, info) in data) {
            set.add(date + "|" + (if (info.off) "1" else "0") + "|" + info.name)
        }
        sp(ctx).edit()
            .putStringSet(P_HOLIDAY_CACHE + year, set)
            .putLong(P_HOLIDAY_CACHE_AT + year, System.currentTimeMillis())
            .apply()
    }

    fun clearHolidayCache(ctx: Context): Int {
        val years = holidayCachedYears(ctx)
        val e = sp(ctx).edit()
        for (y in years) {
            e.remove(P_HOLIDAY_CACHE + y)
            e.remove(P_HOLIDAY_CACHE_AT + y)
        }
        e.apply()
        return years.size
    }

    fun holidayCachedYears(ctx: Context): Set<Int> {
        val out = HashSet<Int>(4)
        for (k in sp(ctx).all.keys) {
            if (k.startsWith(P_HOLIDAY_CACHE) && !k.startsWith(P_HOLIDAY_CACHE_AT)) {
                k.removePrefix(P_HOLIDAY_CACHE).toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    fun holidayCacheTimeText(ctx: Context, year: Int): String {
        val at = sp(ctx).getLong(P_HOLIDAY_CACHE_AT + year, 0L)
        if (at <= 0L) return "更新时间未知"
        return "更新于 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
    }

    fun autoClick(ctx: Context): Boolean = sp(ctx).getBoolean(K_AUTO, true)
    fun setAutoClick(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_AUTO, v).apply()

    /**
     * 下一次闹钟的排定时刻与时段。
     * RTC 是非唤醒闹钟，设备休眠时会顺延到唤醒才送达；送达时刻可能跨天、跨到非工作日，
     * 若按「送达时刻」重算工作日/时段，就会漏卡或打错时段。存下排定信息供 AlarmReceiver 使用。
     */
    data class NextDue(val at: Long, val slot: String)

    fun nextDue(ctx: Context): NextDue? {
        val at = sp(ctx).getLong(K_NEXT_DUE_AT, 0L)
        if (at <= 0L) return null
        val slot = sp(ctx).getString(K_NEXT_DUE_SLOT, "") ?: ""
        return if (slot.isEmpty()) null else NextDue(at, slot)
    }

    fun setNextDue(ctx: Context, at: Long, slot: String) =
        sp(ctx).edit().putLong(K_NEXT_DUE_AT, at).putString(K_NEXT_DUE_SLOT, slot).apply()

    fun clearNextDue(ctx: Context) =
        sp(ctx).edit().remove(K_NEXT_DUE_AT).remove(K_NEXT_DUE_SLOT).apply()

    /** 派发去重：服务重连与顺延闹钟可能同时到达，同一时段 5 分钟内只派发一次 */
    private const val K_LAST_DISPATCH_AT = "last_dispatch_at"
    private const val K_LAST_DISPATCH_SLOT = "last_dispatch_slot"
    private const val DISPATCH_DEDUP_MS = 5 * 60 * 1000L

    fun skipDispatch(ctx: Context, slot: String): Boolean {
        val at = sp(ctx).getLong(K_LAST_DISPATCH_AT, 0L)
        val s = sp(ctx).getString(K_LAST_DISPATCH_SLOT, "") ?: ""
        return s == slot && System.currentTimeMillis() - at < DISPATCH_DEDUP_MS
    }

    fun markDispatch(ctx: Context, slot: String) =
        sp(ctx).edit().putLong(K_LAST_DISPATCH_AT, System.currentTimeMillis())
            .putString(K_LAST_DISPATCH_SLOT, slot).apply()

    // ---------------- 打卡成功通知去重 ----------------

    /**
     * 同一条打卡记录只推一次：键 = 本地日期 + 时段，与 isDone 的日期口径一致。
     * 每次写入顺手清掉非今天的键，集合不会随时间无限增长。
     * @return true 表示本次是首次，调用方应当推送通知
     */
    fun markSuccessNotified(ctx: Context, slot: String): Boolean {
        val prefix = today() + "|"
        val key = prefix + slot
        val set = HashSet(sp(ctx).getStringSet(K_SUCCESS_NOTIFIED, null) ?: emptySet())
        set.removeAll { !it.startsWith(prefix) }
        if (set.contains(key)) return false
        set.add(key)
        sp(ctx).edit().putStringSet(K_SUCCESS_NOTIFIED, set).apply()
        return true
    }

    // ---------------- 每日打卡状态推送 ----------------

    /** 用户级开关，默认关闭；与「启用定时任务」相互独立 */
    const val DEFAULT_STATUS_API = "https://qyapi.weixin.qq.com/cgi-bin/checkin/getcheckindata"
    const val DEFAULT_STATUS_MINUTES = 20 * 60

    fun statusPushEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_STATUS_ENABLED, false)
    fun setStatusPushEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_STATUS_ENABLED, v).apply()

    fun statusPushMinutes(ctx: Context): Int =
        sp(ctx).getInt(K_STATUS_MINUTES, DEFAULT_STATUS_MINUTES)
    fun setStatusPushMinutes(ctx: Context, m: Int) =
        sp(ctx).edit().putInt(K_STATUS_MINUTES, m).apply()

    fun statusApiUrl(ctx: Context): String =
        sp(ctx).getString(K_STATUS_API, DEFAULT_STATUS_API)?.ifBlank { DEFAULT_STATUS_API }
            ?: DEFAULT_STATUS_API
    fun setStatusApiUrl(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_STATUS_API, v.trim()).apply()
    fun resetStatusApiUrl(ctx: Context) = sp(ctx).edit().remove(K_STATUS_API).apply()

    fun statusApiToken(ctx: Context): String = sp(ctx).getString(K_STATUS_TOKEN, "") ?: ""
    fun setStatusApiToken(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_STATUS_TOKEN, v.trim()).apply()

    fun statusUserId(ctx: Context): String = sp(ctx).getString(K_STATUS_USER, "") ?: ""
    fun setStatusUserId(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_STATUS_USER, v.trim()).apply()

    /** 配置自检：URL 里已带 access_token 时不再要求单独填 token */
    fun statusConfigProblem(ctx: Context): String? {
        val url = statusApiUrl(ctx)
        if (url.isBlank()) return "未配置查询接口地址"
        val hasTokenInUrl = url.contains("access_token=", ignoreCase = true)
        if (!hasTokenInUrl && statusApiToken(ctx).isBlank()) return "缺少 access_token"
        if (statusUserId(ctx).isBlank()) return "缺少用户 ID（userid）"
        return null
    }

    /** 配置齐全才走真实 API；否则用本机记录，不假装查过企业微信 */
    fun statusUseApi(ctx: Context): Boolean = statusConfigProblem(ctx) == null

    /** 定时推送同日只推一次，避免 RTC 顺延 + 服务重连造成重复通知 */
    fun statusPushedToday(ctx: Context): Boolean =
        sp(ctx).getString(K_STATUS_LAST_DATE, "") == today()

    fun markStatusPushed(ctx: Context, result: String) =
        sp(ctx).edit()
            .putString(K_STATUS_LAST_DATE, today())
            .putString(
                K_STATUS_LAST_RESULT,
                SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + result
            )
            .apply()

    fun statusLastResult(ctx: Context): String = sp(ctx).getString(K_STATUS_LAST_RESULT, "") ?: ""

    /** 锁屏待办有效期：超过即丢弃，防止跨天残留导致第二天错误补打卡 */
    const val PENDING_STALE_MS = 2 * 60 * 60 * 1000L

    fun setPending(ctx: Context, slot: String) {
        sp(ctx).edit().putBoolean(K_PENDING, true).putString(K_PENDING_SLOT, slot)
            .putLong(K_PENDING_AT, System.currentTimeMillis()).apply()
    }

    fun hasPending(ctx: Context): Boolean = sp(ctx).getBoolean(K_PENDING, false)
    fun pendingSlot(ctx: Context): String = sp(ctx).getString(K_PENDING_SLOT, "上班") ?: "上班"
    fun pendingAgeMs(ctx: Context): Long =
        System.currentTimeMillis() - sp(ctx).getLong(K_PENDING_AT, 0L)
    fun clearPending(ctx: Context) = sp(ctx).edit().putBoolean(K_PENDING, false).apply()

    /**
     * Bug-12：按日期字符串（yyyyMMdd，设备本地时区）记录完成状态。
     * 已知风险（任务书认可，不做过度设计）：系统时间被手动修改或跨时区会错乱；
     * 因此 markDone 时额外输出毫秒时间戳日志，便于事后核对。
     * 按时段分别记录：上班/下班各一个标记，手动打卡检测与自动流程共用。
     */
    fun markDone(ctx: Context, slot: String) {
        val key = if (slot == "上班") K_DONE_UP else K_DONE_DOWN
        sp(ctx).edit().putString(key, today()).apply()
        Log.i("AutoCheckIn", "markDone slot=$slot date=${today()} epoch=${System.currentTimeMillis()}")
    }

    fun isDone(ctx: Context, slot: String): Boolean {
        val key = if (slot == "上班") K_DONE_UP else K_DONE_DOWN
        return sp(ctx).getString(key, "") == today()
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun logs(ctx: Context): String = sp(ctx).getString(K_LOGS, "") ?: ""
    fun clearLogs(ctx: Context) = sp(ctx).edit().remove(K_LOGS).apply()

    /** 只保留最近 150 行，避免无限增长。Bug-10：写日志前强制单行化，防止内部换行导致切割错乱 */
    fun log(ctx: Context, msg: String) {
        val safe = msg.replace('\n', ' ').replace('\r', ' ')
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + safe
        Log.i("AutoCheckIn", line)
        val merged = (line + "\n" + logs(ctx)).lines().take(150).joinToString("\n")
        sp(ctx).edit().putString(K_LOGS, merged).apply()
    }
}
