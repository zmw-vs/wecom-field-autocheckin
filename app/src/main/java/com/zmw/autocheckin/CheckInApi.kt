package com.zmw.autocheckin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 企业微信「获取打卡数据」接口（checkin/getcheckindata）。
 *
 * 该接口需要企业管理员授权的应用 access_token 与本企业 userid，普通员工账号拿不到。
 * 因此调用方只有在 Prefs.statusUseApi() 为 true（配置齐全）时才会走这里；
 * 没凭证时默认用本机记录，绝不会伪造一次「查过企业微信」的结果。
 *
 * 三态语义：只有接口明确给出当日记录 / 明确给出空的当日记录，才返回
 * CHECKED_IN / NOT_CHECKED_IN；其余一切异常、空响应、字段缺失都返回 UNKNOWN，
 * 由上层推中性提示，避免把「查不到」误报成「未打卡」。
 */
object CheckInApi {

    enum class State { CHECKED_IN, NOT_CHECKED_IN, UNKNOWN }

    data class Result(val state: State, val detail: String)

    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 8000
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM-F9660) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun unknown(reason: String) = Result(State.UNKNOWN, reason)

    fun query(ctx: Context): Result {
        val problem = Prefs.statusConfigProblem(ctx)
        if (problem != null) return unknown(problem)

        val urlText = appendToken(Prefs.statusApiUrl(ctx), Prefs.statusApiToken(ctx))
        val (start, end) = todayRangeSeconds()
        val body = JSONObject()
            .put("starttime", start)
            .put("endtime", end)
            .put("useridlist", JSONArray().put(Prefs.statusUserId(ctx)))
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA)
                instanceFollowRedirects = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return unknown("接口返回 HTTP $code")
            val text = conn.inputStream.use { readAll(it) }
            if (text.isBlank()) return unknown("接口返回空内容")
            return parse(text, todayKey())
        } catch (e: Exception) {
            return unknown("接口调用失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 解析：标准企微 checkindata 数组 + 内部代理常用的 checked_in 布尔 */
    fun parse(json: String, today: String): Result {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return unknown("接口返回内容不是有效 JSON")
        }

        val errcode = root.optInt("errcode", 0)
        if (errcode != 0) {
            return unknown(
                "企业微信接口报错 errcode=$errcode " + root.optString("errmsg", "").trim()
            )
        }

        if (root.has("checked_in")) {
            val ok = root.optBoolean("checked_in", false)
            return if (ok) {
                Result(State.CHECKED_IN, "接口确认今日已打卡")
            } else {
                Result(State.NOT_CHECKED_IN, "接口确认今日未打卡")
            }
        }

        val arr = root.optJSONArray("checkindata")
            ?: return unknown("接口未返回打卡数据字段（checkindata）")

        var count = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val raw = item.optLong("checkin_time", 0L)
            if (raw <= 0L) continue
            // 标准接口是秒；个别内部代理返回毫秒，兼容一下
            val millis = if (raw > 100_000_000_000L) raw else raw * 1000L
            if (SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis)) == today) count++
        }
        return if (count > 0) {
            Result(State.CHECKED_IN, "接口今日返回 $count 条打卡记录")
        } else {
            Result(State.NOT_CHECKED_IN, "接口今日无打卡记录")
        }
    }

    private fun appendToken(base: String, token: String): String {
        if (token.isBlank() || base.contains("access_token=", ignoreCase = true)) return base
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + "access_token=" + URLEncoder.encode(token, "UTF-8")
    }

    /** 本地时区当天的 00:00:00 ~ 23:59:59，秒级时间戳 */
    private fun todayRangeSeconds(): Pair<Long, Long> {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis / 1000L
        return Pair(start, start + 24 * 60 * 60 - 1)
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun readAll(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > 1024 * 1024) break
        }
        return out.toString("UTF-8")
    }
}
