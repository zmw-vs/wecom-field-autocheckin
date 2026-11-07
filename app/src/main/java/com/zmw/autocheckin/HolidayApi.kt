package com.zmw.autocheckin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 节假日数据在线拉取。
 *
 * 默认接口：https://timor.tech/api/holiday/year（拼年份 → /year/2026）
 * 用户可在界面上改成任意兼容接口，支持 `{year}` 占位符。
 *
 * 两个关键实现细节：
 * 1. **必须伪装浏览器 UA**。timor.tech 前置了 Cloudflare，Android 默认 UA
 *    （Dalvik/2.1.0）会被判定为爬虫直接返回挑战页，实测带浏览器 UA 才通。
 * 2. **多格式自适应**，不绑定单一供应商：
 *    - timor：顶层 `holiday` 对象，key 为 "MM-dd"，`holiday:true` 放假 / `false` 调休补班
 *    - holiday-cn：顶层 `days` 数组，`isOffDay` 区分
 *    两种都解析不出来就返回 null，交给内置兜底表。
 */
object HolidayApi {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 16; SM-F9660) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 10000

    /** 把用户配置的地址拼成当年请求 URL */
    fun buildUrl(base: String, year: Int): String {
        val b = base.trim()
        return if (b.contains("{year}")) {
            b.replace("{year}", year.toString())
        } else {
            b.trimEnd('/') + "/" + year
        }
    }

    /**
     * 拉取指定年份数据。成功返回 map（key=yyyyMMdd），失败/无数据返回 null。
     * 注意：接口对该年无安排时会返回空对象（如 timor 的 {"code":0,"holiday":{}}），
     * 这种情况视为 null，让上层落到内置兜底表。
     */
    fun fetch(ctx: Context, year: Int): Map<String, HolidayRepo.DayInfo>? {
        val base = Prefs.holidayApi(ctx)
        if (base.isBlank()) return null
        val urlText = buildUrl(base, year)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Prefs.log(ctx, "节假日接口 HTTP $code（$urlText）")
                return null
            }
            val text = conn.inputStream.use { readAll(it) }
            val parsed = parse(text, year)
            if (parsed == null) {
                Prefs.log(ctx, "节假日接口返回无法解析（$urlText），前 120 字符：${text.take(120)}")
            }
            parsed
        } catch (e: Exception) {
            Prefs.log(ctx, "节假日接口请求失败：" + (e.message ?: e.javaClass.simpleName))
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readAll(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > 2 * 1024 * 1024) break // 防御：异常大的响应直接截断
        }
        return out.toString("UTF-8")
    }

    /** 解析入口：依次尝试已知格式，全部失败返回 null */
    fun parse(json: String, year: Int): Map<String, HolidayRepo.DayInfo>? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        parseTimor(root, year)?.let { return it }
        parseHolidayCn(root)?.let { return it }
        return null
    }

    /** timor.tech：{"code":0,"holiday":{"01-01":{"holiday":true,"name":"元旦","date":"2026-01-01"}}} */
    private fun parseTimor(root: JSONObject, year: Int): Map<String, HolidayRepo.DayInfo>? {
        val obj = root.optJSONObject("holiday") ?: return null
        val out = HashMap<String, HolidayRepo.DayInfo>(64)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val item = obj.optJSONObject(k) ?: continue
            // 优先用 date 字段，缺失时用 MM-dd 拼年份
            val dateRaw = item.optString("date", "")
            val key = if (dateRaw.length >= 10) {
                dateRaw.substring(0, 10).replace("-", "")
            } else {
                year.toString() + k.replace("-", "")
            }
            if (key.length != 8 || !key.all { it.isDigit() }) continue
            val off = item.optBoolean("holiday", false)
            val name = item.optString("name", "").ifBlank { if (off) "节假日" else "调休补班" }
            out[key] = HolidayRepo.DayInfo(off, name)
        }
        return if (out.isEmpty()) null else out
    }

    /** holiday-cn：{"year":2026,"days":[{"name":"元旦","date":"2026-01-01","isOffDay":true}]} */
    private fun parseHolidayCn(root: JSONObject): Map<String, HolidayRepo.DayInfo>? {
        val days: JSONArray = root.optJSONArray("days") ?: return null
        val out = HashMap<String, HolidayRepo.DayInfo>(64)
        for (i in 0 until days.length()) {
            val item = days.optJSONObject(i) ?: continue
            val key = item.optString("date", "").replace("-", "")
            if (key.length != 8 || !key.all { it.isDigit() }) continue
            val off = item.optBoolean("isOffDay", false)
            val name = item.optString("name", "").ifBlank { if (off) "节假日" else "调休补班" }
            out[key] = HolidayRepo.DayInfo(off, name)
        }
        return if (out.isEmpty()) null else out
    }
}
