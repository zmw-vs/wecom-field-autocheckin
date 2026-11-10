package com.zmw.autocheckin

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日定时打卡状态推送：组装结果并发送。
 *
 * 数据源二选一：
 * - 默认「本机记录」：读本应用记录的今日上班/下班完成状态，文案强制标注
 *   「非企微服务器数据，请以企业微信为准」，不冒充权威结果。
 * - 配齐 access_token + userid 后自动切「企业微信接口」，走 CheckInApi.query()。
 *
 * 只有明确拿到「已打卡 / 未打卡」证据才用这两个标题；非工作日、尚未到打卡时间、
 * 接口失败/无数据一律推「打卡状态未确认」，避免误报。
 */
object StatusPush {

    /** confirmed=true 表示这条推送给出了明确的已打卡/未打卡结论 */
    data class Report(val title: String, val text: String, val confirmed: Boolean)

    private const val LOCAL_SOURCE = "数据源：本机记录（非企微服务器数据），请以企业微信为准"
    private const val API_SOURCE = "数据源：企业微信接口，请以企业微信为准"

    fun build(ctx: Context): Report =
        if (Prefs.statusUseApi(ctx)) fromApi(ctx) else fromLocal(ctx)

    fun send(ctx: Context, report: Report, markDaily: Boolean = true) {
        if (markDaily) Prefs.markStatusPushed(ctx, report.title + "｜" + report.text)
        Notifier.status(ctx, report.title, report.text)
        Prefs.log(ctx, "状态推送：" + report.title + " · " + report.text)
    }

    private fun fromLocal(ctx: Context): Report {
        val cal = Calendar.getInstance()
        if (!WorkDay.isWorkDay(ctx, cal)) {
            return neutral(
                "今日无需打卡",
                "今日" + WorkDay.describe(ctx, cal) + "，按规则不用打卡，不判定已打卡/未打卡。" +
                    LOCAL_SOURCE
            )
        }

        val cur = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val up = Prefs.upMinutes(ctx)
        val down = Prefs.downMinutes(ctx)
        // 跨夜班跨日，本机按「日期」记的完成状态不足以判定当天该打哪一段，
        // 直接给中性提示，绝不猜一个「未打卡」出来。
        if (up > down) {
            return neutral(
                "跨夜班需人工确认",
                "当前为跨夜班（" + hhmm(up) + " ~ " + hhmm(down) +
                    "），本机记录无法可靠判定当日状态，请在企业微信查看。" +
                    "本机记录仅作参考：" + localBrief(ctx)
            )
        }
        val (upDue, downDue) = dueSlots(up, down, cur)
        if (!upDue && !downDue) {
            return neutral(
                "今天还没到打卡时间",
                "当前 " + hhmm(cur) + "，今日首个打卡时间 " + hhmm(minOf(up, down)) +
                    "，暂不判定状态。" + LOCAL_SOURCE
            )
        }

        val upDone = Prefs.isDone(ctx, "上班")
        val downDone = Prefs.isDone(ctx, "下班")
        val detail = "本机记录：" + part("上班", upDone, upDue) + " / " +
            part("下班", downDone, downDue) + "（检查于 " + nowHm() + "）"
        val miss = ArrayList<String>(2)
        if (upDue && !upDone) miss.add("上班")
        if (downDue && !downDone) miss.add("下班")

        return if (miss.isEmpty()) {
            Report("已打卡", detail + " · " + LOCAL_SOURCE, true)
        } else {
            Report("未打卡", detail + " · 待打：" + miss.joinToString("、") + " · " + LOCAL_SOURCE, true)
        }
    }

    private fun fromApi(ctx: Context): Report {
        val r = CheckInApi.query(ctx)
        return when (r.state) {
            CheckInApi.State.CHECKED_IN ->
                Report("已打卡", r.detail + " · " + API_SOURCE, true)
            CheckInApi.State.NOT_CHECKED_IN ->
                Report("未打卡", r.detail + " · " + API_SOURCE, true)
            CheckInApi.State.UNKNOWN ->
                neutral(
                    "打卡状态未确认",
                    r.detail + "，无法确认当日打卡状态，请在企业微信查看；" +
                        "本机记录仅作参考：" + localBrief(ctx)
                )
        }
    }

    /** API 异常时附上的本机参考值，明确说「仅作参考」，不作为结论 */
    private fun localBrief(ctx: Context): String =
        "上班" + (if (Prefs.isDone(ctx, "上班")) "已打" else "未打") + " / 下班" +
            (if (Prefs.isDone(ctx, "下班")) "已打" else "未打")

    private fun neutral(title: String, text: String) = Report(title, text, false)

    private fun part(name: String, done: Boolean, due: Boolean): String = when {
        !due -> name + " 未到时间"
        done -> name + " ✓已打"
        else -> name + " ✗未打"
    }

    /** 按推送时刻判断哪些时段「应该已经打完」，没到点的不算未打卡 */
    private fun dueSlots(up: Int, down: Int, cur: Int): Pair<Boolean, Boolean> =
        Pair(cur >= up, cur >= down)

    private fun hhmm(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun nowHm(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())
}
