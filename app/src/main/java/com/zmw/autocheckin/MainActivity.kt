package com.zmw.autocheckin

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        // 颜色语义：绿=当前生效/健康，蓝=变体规则，灰=忽略/无，橙=需要注意，红=缺失/异常
        private val COLOR_NEUTRAL = 0xFF202124.toInt()
        private val COLOR_MUTED = 0xFF9AA0A6.toInt()
        private val COLOR_OK = 0xFF1B7F3B.toInt()
        private val COLOR_INFO = 0xFF1565C0.toInt()
        private val COLOR_WARN = 0xFFB26A00.toInt()
        private val COLOR_BAD = 0xFFB3261E.toInt()
    }

    private lateinit var swEnable: Switch
    private lateinit var swAuto: Switch
    private lateinit var btnSettings: Button
    private lateinit var btnStatusPush: Button
    private lateinit var tvState: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
        bind()
        askNotificationPermission()
        // 状态推送是独立开关：打开一次 App 就把每日闹钟排上，
        // 不依赖无障碍服务是否已连接
        CheckInScheduler.scheduleStatus(this)
        // 当年没在线数据时静默拉一次（有缓存则不产生任何请求），完成后刷新界面
        HolidayRepo.ensureYearAsync(this, Calendar.getInstance().get(Calendar.YEAR)) { _, _ -> refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(title("外勤打卡助手"))
        root.addView(
            hint(
                "到点自动打开企业微信并点击打卡按钮。\n" +
                    "不修改定位、不伪造考勤、不读取和上传任何聊天内容/数据，仅通过无障碍实现自动打卡。"
            )
        )

        swEnable = Switch(this).apply { text = "启用定时任务" }
        root.addView(swEnable)

        // 时间、生效日期、节假日规则、自定义不打卡日、节假日数据全部收进这一个入口，
        // 首页只留「开没开」和「今天打没打」，不再一排按钮铺满屏。
        btnSettings = Button(this).apply {
            textSize = 14f
            isAllCaps = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(btnSettings)

        // 每日打卡状态推送：独立的开关 + 每日时间 + 数据源，跟「自动打卡设置」互不干扰
        btnStatusPush = Button(this).apply {
            textSize = 14f
            isAllCaps = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(btnStatusPush)

        swAuto = Switch(this).apply { text = "解锁后自动点击打卡" }
        root.addView(swAuto)

        tvState = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(tvState)

        val btnPerm = Button(this).apply {
            text = "权限检查"
            setOnClickListener { showPermissions() }
        }
        val btnTest = Button(this).apply {
            text = "立即打卡"
            setOnClickListener { punchNow() }
            // 长按 = 演练：只走一遍界面流程，绝不写打卡状态
            setOnLongClickListener {
                dryRunNow()
                true
            }
        }
        root.addView(row(btnPerm, btnTest))
        root.addView(hint("长按「立即打卡」= 演练模式，只走流程不记状态，可反复点。"))

        root.addView(hint("运行日志"))
        tvLog = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(tvLog)

        root.addView(Button(this).apply {
            text = "清空日志"
            setOnClickListener {
                Prefs.clearLogs(this@MainActivity)
                refresh()
            }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun bind() {
        swEnable.setOnCheckedChangeListener { _, v ->
            Prefs.setEnabled(this, v)
            if (v) {
                CheckInScheduler.schedule(this)
            } else {
                CheckInScheduler.cancel(this)
            }
        }
        swAuto.setOnCheckedChangeListener { _, v -> Prefs.setAutoClick(this, v) }
        btnSettings.setOnClickListener { openSettingsDialog() }
        btnStatusPush.setOnClickListener { openStatusPushDialog() }
    }

    private fun refresh() {
        swEnable.isChecked = Prefs.enabled(this)
        swAuto.isChecked = Prefs.autoClick(this)

        val nowCal = Calendar.getInstance()
        val nowYear = nowCal.get(Calendar.YEAR)
        val offCount = Prefs.customOffDays(this).count { it.startsWith(nowYear.toString()) }
        btnSettings.text = "自动打卡设置\n" +
            hhmm(Prefs.upMinutes(this)) + " / " + hhmm(Prefs.downMinutes(this)) +
            "　" + daysText(Prefs.days(this)) +
            "　" + holidayModeText(Prefs.holidayMode(this)) +
            (if (offCount > 0) "　另休 " + offCount + " 天" else "")

        val pushOn = Prefs.statusPushEnabled(this)
        val pushTime = hhmm(Prefs.statusPushMinutes(this))
        val pushSrc = if (Prefs.statusUseApi(this)) "企业微信接口" else "本机记录"
        btnStatusPush.text = "状态推送\n" + (if (pushOn) "开 · " else "关 · ") +
            pushTime + " · " + pushSrc
        btnStatusPush.setTextColor(if (pushOn) COLOR_OK else COLOR_MUTED)

        val ready = CheckInAccessibilityService.isReady()
        val todayCal = nowCal
        val workToday = WorkDay.isWorkDay(this, todayCal)
        val sb = StringBuilder()
        sb.append(if (ready) "无障碍服务：已连接" else "无障碍服务：未连接（自动点击不可用，只会发提醒）")
        sb.append("\n今日：").append(WorkDay.describe(this, todayCal))
        if (workToday) {
            sb.append("   上班").append(if (Prefs.isDone(this, "上班")) " ✓已打" else " 未打")
            sb.append("   下班").append(if (Prefs.isDone(this, "下班")) " ✓已打" else " 未打")
        }
        sb.append("\n状态推送：")
            .append(if (pushOn) "开 · $pushTime · $pushSrc" else "关（不会触发定时推送）")
        val last = Prefs.statusLastResult(this)
        if (last.isNotEmpty()) sb.append("\n上次推送：").append(last.take(70))
        tvState.text = sb.toString()
        // 今日不打卡时用中性色，避免误以为出了故障
        tvState.setTextColor(
            when {
                !ready -> 0xFFB3261E.toInt()
                !workToday -> 0xFF666666.toInt()
                else -> 0xFF1B7F3B.toInt()
            }
        )

        tvLog.text = Prefs.logs(this).ifEmpty { "（暂无日志）" }
    }

    // ---------------- 交互 ----------------

    /**
     * 自动打卡设置：一个入口装全部可调项。
     * 子项点开时先关掉本弹框，避免叠着两层 Dialog 看起来像卡死。
     */
    private fun openSettingsDialog() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val offCount = Prefs.customOffDays(this).count { it.startsWith(year.toString()) }
        val mode = Prefs.holidayMode(this)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }
        var dlg: AlertDialog? = null

        /**
         * 一行 = 标题（灰）+ 值（带颜色）。
         * 值用颜色区分含义：绿=会打卡的规则、蓝=补班也打、灰=忽略、橙=需要注意、红=缺数据，
         * 这样不点进去也能看出当前走的是哪条规则。
         */
        fun item(title: String, value: String, valueColor: Int, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                isFocusable = true
                setBackgroundColor(0xFFF6F7F9.toInt())
                setOnClickListener {
                    dlg?.dismiss()
                    action()
                }
            }
            row.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(valueColor)
                setPadding(0, dp(3), 0, 0)
            })
            box.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
            )
        }

        item("固定打卡时间 · 上班", hhmm(Prefs.upMinutes(this)), COLOR_NEUTRAL) { pickTime(true) }
        item("固定打卡时间 · 下班", hhmm(Prefs.downMinutes(this)), COLOR_NEUTRAL) { pickTime(false) }
        item(
            "生效日期（周几）",
            daysText(Prefs.days(this)),
            if (Prefs.days(this) == 0) COLOR_WARN else COLOR_NEUTRAL
        ) { pickDays() }
        item("节假日规则", holidayModeText(mode), holidayModeColor(mode)) { pickHolidayMode() }
        item(
            "自定义不打卡日（$year 年）",
            if (offCount == 0) "无" else offCount.toString() + " 天",
            if (offCount == 0) COLOR_MUTED else COLOR_WARN
        ) { manageOffDays() }

        val dataColor = when {
            Prefs.holidayCache(this, year).isNotEmpty() -> COLOR_OK
            HolidayRepo.hasData(this, year) -> COLOR_WARN
            else -> COLOR_BAD
        }
        item("节假日数据", holidayDataSummary(year), dataColor) { holidayDataDialog() }

        // 今日最终裁定：把几条规则合起来的结果直接写出来，避免自己猜
        box.addView(TextView(this).apply {
            text = "今日判定：" + WorkDay.describe(this@MainActivity, Calendar.getInstance())
            textSize = 12f
            setTextColor(0xFF37474F.toInt())
            setPadding(dp(4), dp(6), dp(4), dp(8))
        })

        dlg = AlertDialog.Builder(this)
            .setTitle("自动打卡设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    // ---------------- 每日打卡状态推送 ----------------

    /**
     * 一个入口看清四件事：开关状态、每日时间、当前数据源、接口配置缺什么。
     * 颜色沿用自动打卡设置的语义：绿=生效，蓝=真实接口，橙=配置不全，灰=无。
     */
    private fun openStatusPushDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }
        var dlg: AlertDialog? = null

        fun item(title: String, value: String, valueColor: Int, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                isFocusable = true
                setBackgroundColor(0xFFF6F7F9.toInt())
                setOnClickListener {
                    dlg?.dismiss()
                    action()
                }
            }
            row.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(valueColor)
                setPadding(0, dp(3), 0, 0)
            })
            box.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
            )
        }

        val on = Prefs.statusPushEnabled(this)
        val time = hhmm(Prefs.statusPushMinutes(this))
        val useApi = Prefs.statusUseApi(this)
        val problem = Prefs.statusConfigProblem(this)

        item(
            "定时状态推送开关",
            if (on) "已开启 · 每天 $time 推送" else "已关闭 · 不会触发定时推送",
            if (on) COLOR_OK else COLOR_MUTED
        ) { pickStatusSwitch() }

        item("每日推送时间", time, COLOR_NEUTRAL) { pickStatusTime() }

        item(
            "状态数据源",
            if (useApi) "企业微信接口（凭证已配齐）" else "本机记录（推荐 · 无需凭证）",
            if (useApi) COLOR_INFO else COLOR_OK
        ) { editStatusApi() }

        item(
            "接口配置",
            problem ?: "已配置 · " + Prefs.statusUserId(this),
            if (problem == null) COLOR_OK else COLOR_WARN
        ) { editStatusApi() }

        item("上次推送结果", Prefs.statusLastResult(this).ifEmpty { "暂无" }, COLOR_MUTED) {
            AlertDialog.Builder(this)
                .setTitle("上次推送结果")
                .setMessage(Prefs.statusLastResult(this).ifEmpty { "暂无记录" })
                .setPositiveButton("关闭", null)
                .show()
        }

        box.addView(Button(this).apply {
            text = "立即查询并推送一次（不影响每日去重）"
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                dlg?.dismiss()
                testStatusPushNow()
            }
        })

        box.addView(TextView(this).apply {
            text = "说明：没有企业管理员 access_token 时默认用本机记录，推送文案会标注来源；" +
                "接口失败、无数据、非工作日、未到打卡时间都只推「状态未确认」，" +
                "不会误报为已打卡/未打卡。"
            textSize = 12f
            setTextColor(0xFF37474F.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })

        dlg = AlertDialog.Builder(this)
            .setTitle("打卡状态推送")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    private fun pickStatusSwitch() {
        val cur = Prefs.statusPushEnabled(this)
        showChoiceDialog(
            title = "定时状态推送",
            message = "开启后每天在设定时间推送当日打卡状态；关闭时不排闹钟、不推送。\n" +
                "与「启用定时任务」相互独立，关掉自动打卡也能单独用状态推送。",
            options = listOf(
                (if (!cur) "✓ " else "　") + "关闭（不触发定时推送）",
                (if (cur) "✓ " else "　") + "开启（每日一次）"
            ),
            highlight = if (cur) 1 else 0
        ) { which ->
            val v = which == 1
            Prefs.setStatusPushEnabled(this, v)
            if (v) CheckInScheduler.scheduleStatus(this) else CheckInScheduler.cancelStatus(this)
            Prefs.log(this, "状态推送开关改为：" + (if (v) "开" else "关"))
            refresh()
        }
    }

    private fun pickStatusTime() {
        val cur = Prefs.statusPushMinutes(this)
        TimePickerDialog(this, { _, h, m ->
            Prefs.setStatusPushMinutes(this, h * 60 + m)
            CheckInScheduler.scheduleStatus(this)
            Prefs.log(this, "状态推送时间改为 " + hhmm(h * 60 + m))
            refresh()
        }, cur / 60, cur % 60, true).show()
    }

    private fun editStatusApi() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "留空也能用：默认走本机记录，不查接口。\n" +
                "企业微信 getcheckindata 需要企业管理员授权的应用 access_token 和本企业 userid；" +
                "普通员工账号没有权限，填不全就继续用本机记录。\n" +
                "三项填齐后自动切换为真实接口查询。"
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        fun labeled(label: String, value: String, password: Boolean): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = 12f
            })
            val et = EditText(this).apply {
                setText(value)
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                setTextSize(13f)
            }
            box.addView(et)
            return et
        }

        val etUrl = labeled("查询接口地址", Prefs.statusApiUrl(this), false)
        val etToken = labeled("access_token（仅本地保存）", Prefs.statusApiToken(this), true)
        val etUser = labeled("用户 ID（userid）", Prefs.statusUserId(this), false)

        AlertDialog.Builder(this)
            .setTitle("企业微信接口配置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val u = etUrl.text.toString().trim()
                if (u.isEmpty()) Prefs.resetStatusApiUrl(this) else Prefs.setStatusApiUrl(this, u)
                Prefs.setStatusApiToken(this, etToken.text.toString())
                Prefs.setStatusUserId(this, etUser.text.toString())
                CheckInScheduler.scheduleStatus(this)
                Prefs.log(
                    this, "状态接口配置已更新，数据源：" +
                        if (Prefs.statusUseApi(this)) "企业微信接口" else "本机记录"
                )
                refresh()
            }
            .setNeutralButton("清空凭证") { _, _ ->
                Prefs.resetStatusApiUrl(this)
                Prefs.setStatusApiToken(this, "")
                Prefs.setStatusUserId(this, "")
                CheckInScheduler.scheduleStatus(this)
                Prefs.log(this, "已清空状态接口凭证，回到本机记录")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手动验一次文案与接口连通性，不写「今日已推送」标记，不影响定时去重 */
    private fun testStatusPushNow() {
        Toast.makeText(this, "正在查询打卡状态…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            try {
                val report = StatusPush.build(app)
                StatusPush.send(app, report, false)
                runOnUiThread {
                    Toast.makeText(this, "已推送：" + report.title, Toast.LENGTH_LONG).show()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this, "查询失败：" + (e.message ?: "未知错误"), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** 节假日规则对应的强调色 */
    private fun holidayModeColor(mode: Int): Int = when (mode) {
        Prefs.MODE_IGNORE -> COLOR_MUTED
        Prefs.MODE_OFF_HOLIDAY_WITH_MAKEUP -> COLOR_INFO
        else -> COLOR_OK
    }

    private fun holidayDataSummary(year: Int): String {
        if (Prefs.holidayCache(this, year).isNotEmpty()) return "在线 · $year 已缓存"
        if (HolidayRepo.hasData(this, year)) return "内置兜底 · 可刷新"
        return "无数据 · 点此设置"
    }

    private fun pickTime(isUp: Boolean) {
        val cur = if (isUp) Prefs.upMinutes(this) else Prefs.downMinutes(this)
        TimePickerDialog(this, { _, h, m ->
            if (isUp) Prefs.setUp(this, h * 60 + m) else Prefs.setDown(this, h * 60 + m)
            CheckInScheduler.schedule(this)
            refresh()
        }, cur / 60, cur % 60, true).show()
    }

    private fun pickDays() {
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        var mask = Prefs.days(this)
        val checked = BooleanArray(7) { (mask and (1 shl it)) != 0 }

        AlertDialog.Builder(this)
            .setTitle("生效日期")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                mask = if (isChecked) mask or (1 shl which) else mask and (1 shl which).inv()
            }
            .setPositiveButton("保存") { _, _ ->
                Prefs.setDays(this, mask)
                CheckInScheduler.schedule(this)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 节假日规则三档。用单选而不是两个开关，避免「节假日跳过 + 补班也跳过」这类
     * 互相矛盾的组合，用户一次只能选一种明确策略。
     */
    private fun pickHolidayMode() {
        val cur = Prefs.holidayMode(this)
        val base = listOf(
            "忽略节假日（只看勾选的周几）",
            "法定节假日不打卡（推荐）",
            "法定节假日不打卡 + 调休补班日照常打卡"
        )
        val labels = base.mapIndexed { i, s -> (if (i == cur) "✓ " else "　") + s }
        showChoiceDialog(
            title = "节假日规则",
            message = "数据优先取在线接口（默认 timor.tech），内置表仅作离线兜底使用。\n" +
                "选第三项时，调休补班的周六/周日会照常打卡。\n" +
                "当前生效的是绿色高亮那一档。",
            options = labels,
            highlight = cur
        ) { which ->
            Prefs.setHolidayMode(this, which)
            Prefs.log(this, "节假日规则改为：" + holidayModeText(which))
            CheckInScheduler.schedule(this)
            refresh()
        }
    }

    /**
     * 自定义不打卡日：按「当年」管理。
     * 只列今年的记录，加日期也限制在今年范围内——放假日都是按年度排的，
     * 混着往年数据看着像乱账，且往年记录对今天没有任何影响。
     */
    private fun manageOffDays() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val keys = Prefs.customOffDays(this).filter { it.startsWith(year.toString()) }.sorted()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "针对 $year 年：公司额外放假、年假、请假等内置表覆盖不到的日子，指定后当天不打卡。"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 0, 0, dp(12))
        })

        var dlg: AlertDialog? = null
        for (k in keys) {
            box.addView(
                Button(this).apply {
                    text = fmtKey(k) + "　✕ 点击删除"
                    textSize = 14f
                    isAllCaps = false
                    setOnClickListener {
                        dlg?.dismiss()
                        Prefs.removeOffDay(this@MainActivity, k)
                        Prefs.log(this@MainActivity, "移除自定义不打卡日 $k")
                        CheckInScheduler.schedule(this@MainActivity)
                        refresh()
                        manageOffDays()
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            )
        }

        box.addView(
            Button(this).apply {
                text = "＋ 添加 $year 年的日期"
                textSize = 15f
                isAllCaps = false
                setOnClickListener {
                    dlg?.dismiss()
                    pickAddOffDay(year)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        dlg = AlertDialog.Builder(this)
            .setTitle("自定义不打卡日（$year 年）")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    private fun pickAddOffDay(year: Int) {
        val c = Calendar.getInstance()
        val picker = DatePickerDialog(this, { _, y, m, d ->
            val key = String.format(Locale.US, "%04d%02d%02d", y, m + 1, d)
            Prefs.addOffDay(this, key)
            Prefs.log(this, "新增自定义不打卡日 $key")
            CheckInScheduler.schedule(this)
            refresh()
            manageOffDays()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        // 只允许选当年，避免选到明年让人以为今年生效
        val min = Calendar.getInstance().apply {
            set(year, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val max = Calendar.getInstance().apply {
            set(year, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 0)
        }
        picker.datePicker.minDate = min.timeInMillis
        picker.datePicker.maxDate = max.timeInMillis
        picker.show()
    }

    /**
     * 通用选项弹框：用自定义 View 铺真按钮，不用 setItems。
     *
     * 原因：AlertDialog 同时 setItems + setMessage 时，部分 ROM / 系统版本会把
     * ListView 高度算成 0，用户只看到提示文字、看不到任何可选项（实测踩到）。
     * 自定义布局下按钮高度由我们自己决定，任何机型都不会消失。
     */
    private fun showChoiceDialog(
        title: String,
        message: String?,
        options: List<String>,
        highlight: Int = -1,
        onPick: (Int) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(4))
        }
        if (!message.isNullOrEmpty()) {
            box.addView(TextView(this).apply {
                text = message
                textSize = 13f
                setTextColor(0xFF555555.toInt())
                setPadding(0, 0, 0, dp(12))
            })
        }

        var dlg: AlertDialog? = null
        for ((i, label) in options.withIndex()) {
            val picked = i == highlight
            val b = Button(this).apply {
                text = label
                textSize = 15f
                isAllCaps = false
                if (picked) {
                    // 当前生效的那一档：浅绿底 + 绿字加粗，一眼能看出走的是哪条规则
                    setBackgroundColor(0xFFE3F3E7.toInt())
                    setTextColor(COLOR_OK)
                    typeface = Typeface.DEFAULT_BOLD
                }
                setOnClickListener {
                    dlg?.dismiss()
                    onPick(i)
                }
            }
            box.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            )
        }

        dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
    }

    // ---------------- 节假日数据管理 ----------------

    private fun holidayDataDialog() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val cached = Prefs.holidayCache(this, year)
        val sb = StringBuilder()
        sb.append("当前年份：").append(year).append('\n')
        sb.append("数据来源：").append(HolidayRepo.sourceText(this, year)).append('\n')
        if (cached.isNotEmpty()) {
            val offCount = cached.count { it.split('|').getOrNull(1) == "1" }
            val workCount = cached.count { it.split('|').getOrNull(1) == "0" }
            sb.append("放假 ").append(offCount).append(" 天 · 调休补班 ").append(workCount).append(" 天\n")
        }
        val years = HolidayRepo.cachedYears(this)
        if (years.isNotEmpty()) {
            sb.append("已缓存年份：").append(years.joinToString("、")).append('\n')
        }
        sb.append("\n接口：").append(Prefs.holidayApi(this))

        showChoiceDialog(
            title = "节假日数据",
            message = sb.toString(),
            options = listOf("立即从接口更新", "修改接口地址", "清除本地缓存")
        ) { idx ->
            when (idx) {
                0 -> refreshHoliday(year)
                1 -> editHolidayApi()
                else -> {
                    val n = Prefs.clearHolidayCache(this)
                    HolidayRepo.invalidate()
                    Prefs.log(this, "已清除 $n 年节假日缓存，回到内置兜底表")
                    CheckInScheduler.schedule(this)
                    refresh()
                    Toast.makeText(this, "已清除 $n 年缓存", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshHoliday(year: Int) {
        Toast.makeText(this, "正在更新 $year 年数据…", Toast.LENGTH_SHORT).show()
        HolidayRepo.refreshAsync(this, year) { ok, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            // 数据变了，工作日判定可能变，必须重排闹钟
            if (ok) CheckInScheduler.schedule(this)
            refresh()
        }
    }

    private fun editHolidayApi() {
        val et = EditText(this).apply {
            setText(Prefs.holidayApi(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = false
            setTextSize(13f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "支持 {year} 占位符；不含占位符时自动在末尾拼接年份。\n" +
                "默认：${Prefs.DEFAULT_HOLIDAY_API}"
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 0, 0, dp(10))
        })
        box.addView(et)

        AlertDialog.Builder(this)
            .setTitle("节假日接口地址")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty()) Prefs.resetHolidayApi(this) else Prefs.setHolidayApi(this, v)
                HolidayRepo.invalidate()
                Prefs.log(this, "节假日接口改为：" + Prefs.holidayApi(this))
                refresh()
                refreshHoliday(Calendar.getInstance().get(Calendar.YEAR))
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Prefs.resetHolidayApi(this)
                HolidayRepo.invalidate()
                Prefs.log(this, "节假日接口已恢复默认")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPermissions() {
        val labels = arrayOf(
            "无障碍服务：" + (if (CheckInAccessibilityService.isReady()) "已连接" else "未连接"),
            "通知权限：" + (if (NotificationManagerCompat.from(this).areNotificationsEnabled()) "已开启" else "未开启"),
            "精确闹钟：" + (if (exactAlarmOk()) "已允许" else "未允许"),
            "电池优化：" + (if (batteryOk()) "已忽略" else "未忽略，可能被杀"),
            "自启动（三星）：已允许则点此项确认" + "\n（重启后能自动恢复定时）"
        )

        AlertDialog.Builder(this)
            .setTitle("点击一项去开启")
            // Bug-11：Android 13+ 应用未被手动打开过一次收不到开机广播，代码无法绕过，
            // 只能在 UI 提示；无障碍服务被系统杀掉同样需手动重新开启。
            .setMessage(
                "注意：安装后请至少手动打开一次本应用，开机广播才能生效；\n" +
                    "若系统电池优化杀掉了无障碍服务，需到这里重新手动开启。\n" +
                    "「自启动」= 电池 → 后台控制 → 自动运行应用 → 打开本应用开关。"
            )
            .setItems(labels) { _, which -> openSetting(which) }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openSetting(which: Int) {
        val pkgUri = Uri.parse("package:" + packageName)
        val intent: Intent = when (which) {
            0 -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            1 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            }
            3 -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            else -> {
                // 三星「自动运行应用」页面被安全策略禁止外部直接启动（已实测 SecurityException），
                // 退一步跳智能管理器的电池页（com.samsung.android.sm_cn，已实测可跳），
                // 用户再点「后台控制 → 自动运行应用」两步到达。
                val sm = Intent().setClassName(
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (sm.resolveActivity(packageManager) != null) sm
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            }
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        }
    }

    /**
     * 立即打卡：走真实流程。
     * 与到点自动执行的代码路径完全一致（会真的点下去、会记为今日已打），
     * 不是演练——所以必须让用户先明确选哪个时段再执行。
     */
    private fun punchNow() {
        if (!CheckInAccessibilityService.isReady()) {
            AlertDialog.Builder(this)
                .setTitle("还没开启无障碍")
                .setMessage("自动点击需要开启「外勤打卡助手」的无障碍服务。")
                .setPositiveButton("去开启") { _, _ -> openSetting(0) }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 时段决定程序去点「上班打卡」还是「下班打卡」按钮，手动触发不能靠时间推断
        val upDone = Prefs.isDone(this, "上班")
        val downDone = Prefs.isDone(this, "下班")
        showChoiceDialog(
            title = "打卡哪一项？（真实执行）",
            message = "会真实完成打卡，成功后记为今日已打，和到点自动执行完全一样。\n" +
                "点下去后会打开企业微信：工作台打卡 → 右上角“…” → 进入应用 → 点打卡按钮。",
            options = listOf(
                "上班卡" + (if (upDone) "（今日已打，可补点）" else ""),
                "下班卡" + (if (downDone) "（今日已打，可补点）" else "")
            )
        ) { idx -> runReal(if (idx == 0) "上班" else "下班") }
    }

    /** 演练：dryRun 通道，走完整的界面点击链路，但不写 pending / 不记已打 */
    private fun dryRunNow() {
        if (!CheckInAccessibilityService.isReady()) {
            Toast.makeText(this, "无障碍服务未连接，先开启再演练", Toast.LENGTH_LONG).show()
            return
        }
        showChoiceDialog(
            title = "演练哪一项？",
            message = "演练只走界面流程（工作台→…→进入应用→点打卡），不会写入打卡状态，可以反复点。",
            options = listOf("上班卡", "下班卡")
        ) { idx ->
            val slot = if (idx == 0) "上班" else "下班"
            Prefs.log(this, "[演练] 触发 $slot 卡")
            val ok = CheckInAccessibilityService.requestCheckIn(
                this, slot, true, force = true, dryRun = true
            )
            Toast.makeText(
                this,
                if (ok) "已开始演练，观察企业微信" else "触发失败，看下方日志",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun runReal(slot: String) {        // 任务书五-5：执行前先打印完整配置与状态日志，便于调试
        Prefs.log(
            this,
            "手动触发 $slot 卡（真实打卡）：上班=" + hhmm(Prefs.upMinutes(this)) +
                    " 下班=" + hhmm(Prefs.downMinutes(this)) +
                    " 自动点击=" + Prefs.autoClick(this) +
                    " 今日上班已打=" + Prefs.isDone(this, "上班") +
                    " 今日下班已打=" + Prefs.isDone(this, "下班") +
                    " 今日=" + WorkDay.describe(this, Calendar.getInstance())
        )
        val started = CheckInAccessibilityService.requestCheckIn(this, slot, true, force = true)
        if (started) {
            Toast.makeText(this, "已开始 $slot 卡真实打卡，请观察企业微信", Toast.LENGTH_SHORT).show()
        } else {
            // force 通道已排除「已打卡」，走到这里只可能是服务未连接或已有流程在跑
            Toast.makeText(
                this,
                "触发失败：无障碍服务未连接，或上一次流程仍在执行（看下方日志）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------- 工具 ----------------

    private fun exactAlarmOk(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun batteryOk(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
    }

    private fun hhmm(minutes: Int): String = String.format("%02d:%02d", minutes / 60, minutes % 60)

    private fun daysText(mask: Int): String {
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val sb = StringBuilder()
        for (i in 0..6) {
            if ((mask and (1 shl i)) != 0) sb.append(names[i])
        }
        return if (sb.isEmpty()) "未选择" else sb.toString()
    }

    private fun holidayModeText(mode: Int): String = when (mode) {
        Prefs.MODE_IGNORE -> "忽略"
        Prefs.MODE_OFF_HOLIDAY_WITH_MAKEUP -> "法定假不打 · 补班打"
        else -> "法定假不打"
    }

    /** yyyyMMdd -> yyyy-MM-dd 周X */
    private fun fmtKey(key: String): String = try {
        val parsed = SimpleDateFormat("yyyyMMdd", Locale.US).parse(key)
        if (parsed == null) key else SimpleDateFormat("yyyy-MM-dd EEE", Locale.CHINA).format(parsed)
    } catch (_: Exception) {
        key
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun row(vararg views: View): LinearLayout {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach {
            ll.addView(
                it,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        return ll
    }

    private fun title(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 20f
        setPadding(0, 0, 0, dp(8))
    }

    private fun hint(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xFF666666.toInt())
        setPadding(0, 0, 0, dp(16))
    }
}
