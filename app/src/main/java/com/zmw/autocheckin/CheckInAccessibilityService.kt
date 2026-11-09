package com.zmw.autocheckin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 只做「打开企微 → 进入打卡应用 → 找到打卡按钮 → 点一下」的 UI 自动化。
 * 不读取页面文本内容之外的任何信息，不修改定位，不构造请求。
 *
 * 本文件按任务书修补：Bug-2/3/5/6/7/8/9 与省电专项 1/2/3/4/6/8。
 *
 * 打卡入口是「预览页」，必须走完三步才能到真正的打卡按钮：
 *   工作台点「打卡」→ 右上角「···」→「进入应用」→「上班打卡 / 下班打卡」
 * 因此流程改成阶段状态机，每步独立限次，避免在原地反复瞎点。
 */
// Bug-2：统一安全回收。API 33+ recycle() 为空操作（框架自管对象池），
// Android 10~12 上有实际回收意义；包一层异常兜底防止个别 ROM 行为差异。
private fun AccessibilityNodeInfo?.safeRecycle() {
    try {
        this?.recycle()
    } catch (_: Exception) {
    }
}

class CheckInAccessibilityService : AccessibilityService() {

    companion object {
        const val WEWORK = "com.tencent.wework"

        // 省电-3：轮询间隔放宽。多步流程（预览页加载 + 菜单展开）比原来长，
        // 轮数上调到 30 轮 ≈ 55s 上限；正常一次只走十几轮就结束。
        private const val TICK = 1700L
        private const val MAX_TICK = 30

        /**
         * 各阶段独立限次。必须限制：点完一次后页面可能停在同一界面，
         * 无上限会每 2 秒重复点，既耗电又可能反复触发拍照/人脸弹窗。
         */
        private const val MAX_ENTRY = 3   // 工作台「打卡」入口
        private const val MAX_DOTS = 2    // 右上角「···」
        private const val MAX_ENTER = 2   // 菜单里的「进入应用」
        private const val MAX_PUNCH = 2   // 真正的「上班打卡/下班打卡」
        private const val MAX_SCROLLS = 5 // 工作台翻页次数（打卡应用可能不在首屏）

        private val DONE_TEXTS = listOf("打卡成功", "已打卡", "更新打卡")

        /**
         * 点过「确认打卡」之后用来判成功的文案，比 DONE_TEXTS 宽。
         * 外勤确认完企微不一定弹「打卡成功」四个字，也可能直接显示打卡时间/已打卡栏位。
         * 只用于确认后的收尾判定，不影响 passiveScan 和「当前已是打卡状态」的严格判定。
         */
        private val SUCCESS_TEXTS = listOf(
            "打卡成功", "已打卡", "更新打卡", "打卡完成", "今日已打卡", "打卡时间"
        )

        /** 点完「确认打卡」最多再等 4 轮（约 7 秒），够企微刷新结果，又不至于空转耗电 */
        private const val CONFIRM_WAIT_TICKS = 4

        /** 预览页菜单里的「进入应用」，不同企微版本叫法略有差异 */
        private val ENTER_LABELS = listOf("进入应用", "打开应用", "进入小程序", "打开小程序", "立即进入")

        /** 右上角三个点的常见无障碍描述 */
        private val DOTS_LABELS = listOf("更多", "更多选项", "更多操作", "更多功能", "更多菜单", "菜单", "···", "...", "⋯")

        // Bug-5：静态引用，onServiceConnected 赋值、onDestroy/onUnbind 置空，
        // 调用方一律先判空并 try-catch（进程被直接 kill 时 onDestroy 不会执行）
        @Volatile
        private var alive: CheckInAccessibilityService? = null

        fun isReady(): Boolean = alive != null

        /**
         * @param force  跳过「今日已打卡」检查（手动点「立即打卡」时用）
         * @param dryRun 演练通道：不写 pending、完成后不写 markDone、通知带 [测试] 前缀。
         *               默认 false = 真实打卡，状态写入与定时触发完全一致。
         * @return true 表示已成功进入执行流程；false 表示被跳过/服务不可用/已有流程在跑
         */
        fun requestCheckIn(
            ctx: Context,
            slot: String,
            auto: Boolean,
            force: Boolean = false,
            dryRun: Boolean = false
        ): Boolean {
            if (!force && Prefs.isDone(ctx, slot)) {
                Prefs.log(ctx, "今日$slot 已打卡（检测到手动打卡），跳过")
                return false
            }
            if (!dryRun) Prefs.setPending(ctx, slot)
            val svc = alive
            if (svc == null) {
                if (force) {
                    Prefs.log(ctx, "执行失败：无障碍服务未运行")
                    return false
                }
                Prefs.clearPending(ctx)
                Prefs.log(ctx, "无障碍服务未运行，降级为通知提醒")
                Notifier.alert(ctx, "该打卡了（$slot）", "无障碍服务未开启，请手动完成打卡")
                return false
            }
            if (!auto) {
                Notifier.alert(ctx, "该打卡了（$slot）", "点这里打开企业微信完成打卡", openWework = true)
                return false
            }
            return try {
                svc.begin(slot, dryRun)
            } catch (e: Exception) {
                Prefs.log(ctx, "触发自动打卡失败：" + e.message)
                if (!force) Notifier.alert(ctx, "打卡未完成（$slot）", "自动触发失败，请手动补一下")
                false
            }
        }

        fun setPending(ctx: Context, slot: String) = Prefs.setPending(ctx, slot)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { tick() }

    private var running = false
    private var ticks = 0
    private var slot = "上班"
    private var wentWorkbench = false
    private var lastPassiveScan = 0L

    /** 各阶段点击计数，分别受限 */
    private var entryClicks = 0
    private var dotsClicks = 0
    private var enterClicks = 0
    private var punchClicks = 0

    /** 是否已真的进到打卡应用（页面出现上班打卡/下班打卡/外出打卡，或点过「进入应用」） */
    private var enteredApp = false

    /** 上一次点击「打卡」入口的轮次，用于控制「···」的最小等待 */
    private var lastEntryTick = 0

    /** 工作台翻页次数与上次翻页轮次 */
    private var scrolls = 0
    private var lastScrollTick = 0

    /** 上一次点击打卡按钮的轮次：点完迟迟没结果才去走「··· → 进入应用」 */
    private var lastPunchTick = 0

    /**
     * 点过「确认打卡」（外勤弹窗最终确认）的轮次。
     * 点完只剩拍照/人脸/位置这类必须人参与的步骤，等几轮没出「打卡成功」就交还用户。
     */
    private var confirmTick = -1

    /** true = 本次是「立即测试」触发：不写打卡状态、不清生产 pending、通知带 [测试] 前缀 */
    private var testing = false

    /** 本次流程起始时刻，用于 watchdog：避免异常导致 running 永久卡死后再也点不动 */
    private var beginAt = 0L

    // Bug-3：防止 onServiceConnected 被系统多次回调导致广播重复注册崩溃
    @Volatile
    private var unlockRxRegistered = false

    private val unlockRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            val ctx = c ?: return
            // 省电-6：今日两时段均已完成，解锁后零工作直接返回
            if (Prefs.isDone(ctx, "上班") && Prefs.isDone(ctx, "下班")) return
            if (!Prefs.hasPending(ctx)) return
            val slot = Prefs.pendingSlot(ctx)
            if (Prefs.pendingAgeMs(ctx) > Prefs.PENDING_STALE_MS) {
                Prefs.clearPending(ctx)
                // Bug-7：超期丢弃不能只写日志，必须通知用户避免静默漏卡
                Prefs.log(ctx, "锁屏待办已过期（超过2小时），丢弃")
                Notifier.alert(ctx, "打卡任务已超期", "锁屏待办超时未执行，请手动打卡", openWework = true)
                return
            }
            if (Prefs.isDone(ctx, slot)) {
                Prefs.clearPending(ctx)
                Prefs.log(ctx, "解锁时发现今日$slot 已打卡，取消补执行")
                return
            }
            Prefs.log(ctx, "检测到解锁，继续未完成的打卡：$slot")
            try {
                begin(slot)
            } catch (e: Exception) {
                Prefs.log(ctx, "解锁补执行失败：" + e.message)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = this
        // Bug-3：只在未注册时注册
        if (!unlockRxRegistered) {
            ContextCompat.registerReceiver(
                this,
                unlockRx,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            unlockRxRegistered = true
        }
        Prefs.log(this, "无障碍服务已连接")
        // 闹钟自愈兜底：重启后无障碍服务由系统自动重连（enabled_accessibility_services
        // 是持久化设置），比 BOOT_COMPLETED 广播更可靠——三星「自动运行」开关拦得住
        // 开机广播，拦不住系统自己重连无障碍。schedule() 幂等，重复调用只覆盖闹钟。
        try {
            CheckInScheduler.schedule(this)
        } catch (e: Exception) {
            Prefs.log(this, "服务重连时重排定时失败：" + e.message)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Bug-3 / Bug-5：系统解绑服务时同样注销广播、释放静态引用
        unregisterUnlockRx()
        alive = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterUnlockRx()
        alive = null
        // 省电-4：销毁时清空所有残留 Runnable
        handler.removeCallbacksAndMessages(null)
        Prefs.log(this, "无障碍服务已断开")
        super.onDestroy()
    }

    private fun unregisterUnlockRx() {
        if (unlockRxRegistered) {
            try {
                unregisterReceiver(unlockRx)
            } catch (_: Exception) {
            }
            unlockRxRegistered = false
        }
    }

    // Bug-8：系统要求中断（无障碍反馈通道断开等）时终止进行中的流程与轮询
    override fun onInterrupt() {
        if (running) {
            running = false
            testing = false
            handler.removeCallbacksAndMessages(null)
            Prefs.log(this, "服务被中断，终止当前打卡流程")
        }
    }

    /**
     * 被动检测入口：用户手动打卡必经企微打卡页。
     * 省电-1：事件入口第一件事判断双时段完成，完成则零解析直接返回。
     * 省电-2：限频 1800ms；事件源由 config 的 packageNames 限定为企微。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (Prefs.isDone(this, "上班") && Prefs.isDone(this, "下班")) return
        if (e.packageName?.toString() != WEWORK) return
        if (running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPassiveScan < 1800L) return
        lastPassiveScan = now
        handler.post { passiveScan() }
    }

    /**
     * Bug-6 增强：仅凭「已打卡」文案标记完成太宽松——
     * 停留在昨天的旧打卡页、企微文案变化都会误判。
     * 现要求以下三重条件之一确认页面属于「今天」：
     *   a) 页面含今日日期文本（M月d日）；
     *   b) 识别到「打卡成功」（仅在刚打卡后出现的即时文案）；
     *   c) 页面出现与当前分钟一致的 HH:mm（打卡页的实时时间）。
     */
    private fun passiveScan() {
        if (running) return
        if (Prefs.isDone(this, "上班") && Prefs.isDone(this, "下班")) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != WEWORK) {
            root.safeRecycle()
            return
        }

        // 防误判：打卡记录/统计/月历/补卡页同样会出现「已打卡」历史文案
        if (exists(root, listOf("打卡记录", "打卡统计", "月历", "补卡申请"))) {
            root.safeRecycle()
            return
        }

        // 只认打卡主页面：需同时有上下班栏位，或有外出打卡入口
        val looksCheckinPage = (exists(root, listOf("上班")) && exists(root, listOf("下班")))
                || exists(root, listOf("外出打卡"))
        if (!looksCheckinPage) {
            root.safeRecycle()
            return
        }

        val doneText = firstDoneText(root)
        if (doneText == null) {
            root.safeRecycle()
            return
        }

        val hasToday = exists(root, listOf(todayCn()))
        val hasNow = exists(root, listOf(nowHm()))
        if (!hasToday && !doneText.contains("打卡成功") && !hasNow) {
            root.safeRecycle()
            Prefs.log(this, "页面出现「$doneText」但无法确认属于今天，保守不标记")
            return
        }

        val cal = Calendar.getInstance()
        val cur = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = SlotResolver.slotForMinute(this, cur)
        root.safeRecycle()
        if (!Prefs.isDone(this, s)) {
            Prefs.markDone(this, s)
            Prefs.log(this, "检测到手动打卡：今日$s 已完成（识别文案=$doneText 今日标识=${hasToday || hasNow}）")
        }
    }

    private fun todayCn(): String = SimpleDateFormat("M月d日", Locale.US).format(Date())

    private fun nowHm(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    private fun firstDoneText(root: AccessibilityNodeInfo): String? {
        for (t in DONE_TEXTS) {
            if (exists(root, listOf(t))) return t
        }
        return null
    }

    fun begin(slotName: String, isTest: Boolean = false): Boolean {
        if (running) {
            // watchdog：正常流程最长 ~55s 必然 finish，超过 120s 还挂着说明异常中断了，
            // 强制重置，否则「立即测试」会被永久锁死
            if (SystemClock.elapsedRealtime() - beginAt > 120_000L) {
                Prefs.log(this, "上一次流程超时未结束，强制重置后重新开始")
                handler.removeCallbacksAndMessages(null)
                running = false
            } else {
                Prefs.log(this, "已有打卡流程在执行中，忽略本次触发")
                return false
            }
        }
        running = true
        testing = isTest
        beginAt = SystemClock.elapsedRealtime()
        ticks = 0
        entryClicks = 0
        dotsClicks = 0
        enterClicks = 0
        punchClicks = 0
        enteredApp = false
        lastEntryTick = 0
        scrolls = 0
        lastScrollTick = 0
        lastPunchTick = 0
        confirmTick = -1
        wentWorkbench = false
        slot = slotName

        Prefs.log(this, (if (isTest) "[测试] 开始自动打卡：" else "开始自动打卡：") + slot)

        val launch = packageManager.getLaunchIntentForPackage(WEWORK)
        if (launch == null) {
            finish(false, "未安装企业微信")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            finish(false, "启动企业微信失败：" + e.message)
            return false
        }
        handler.postDelayed(tickRunnable, 1800)
        return true
    }

    private fun tick() {
        if (!running) return
        ticks++
        if (ticks > MAX_TICK) {
            // Bug-9：超时时 dump 页面上含「打卡」的候选文本 + 顶栏节点，便于排查匹配失败原因
            val root = rootInActiveWindow
            val dump = if (root != null && root.packageName?.toString() == WEWORK) {
                dumpCandidates(root) + " " + dumpTopBar(root)
            } else {
                "（企业微信不在前台）"
            }
            root.safeRecycle()
            finish(false, "超时未完成打卡（停在：" + stageName() + "） $dump")
            return
        }

        val root = rootInActiveWindow

        // 「确认打卡」已点：工具能做的最后一步已经做完，后面交给企微自己校验。
        // 这段判定必须放在「root 是否企微」检查之前：实测打卡成功后企微页面会收起，
        // 或手机自动息屏导致 rootInActiveWindow 为 null；原先放在检查之后，永远走不到，
        // 一路空转到 MAX_TICK 超时——明明打卡成功却弹「请手动补一下」（11-08、11-09 连着三次）。
        if (confirmTick >= 0) {
            handleAfterConfirm(root)
            return
        }

        if (root == null || root.packageName?.toString() != WEWORK) {
            root.safeRecycle()
            // 已经点过打卡按钮，企微却不在前台：多半是打卡后页面收起、手机息屏或跳去拍照页。
            // 不能一路空转到超时判「未完成」，否则就是「打完了还催补卡」。
            // 这里没走到「确认打卡」，留一条中性提醒（不带补卡字样）防止真需要拍照时漏卡。
            if (punchClicks > 0 && ticks - lastPunchTick >= 3) {
                finishSubmitted(
                    "已点打卡按钮（$slot），企微页面已收起，结果以企微为准",
                    remind = true
                )
                return
            }
            handler.postDelayed(tickRunnable, TICK)
            return
        }

        // 实测两种入口形态都存在：
        //   A) 点工作台「打卡」直达打卡应用（页面直接有上班/下班打卡按钮）；
        //   B) 打开的是预览页，必须右上角「···」→「进入应用」才到真按钮。
        // 所以不能靠「看到上班打卡就算进了应用」切阶段（预览页也会显示这个文案），
        // 改成按预算并行尝试：先直接点按钮，点不动再走「···」链路。

        // 0) 打卡按钮已在眼前：直接点。确认弹窗（不在打卡范围内 → 确认打卡）优先级最高
        if (punchClicks < MAX_PUNCH) {
            for (label in listOf("确认打卡", slot + "打卡", "外出打卡")) {
                val btn = clickTarget(root, listOf(label))
                if (btn == null) continue
                punchClicks++
                lastPunchTick = ticks
                if (label == "确认打卡") confirmTick = ticks
                Prefs.log(this, "点击打卡按钮（$label，第 $punchClicks 次）")
                clickNode(btn)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, if (punchClicks == 1) 2600 else 2000)
                return
            }
        }

        // 0.5) 成功文案：「打卡成功」是即时反馈可信；「已打卡/更新打卡」要页面像真打卡页
        val doneText = firstDoneText(root)
        if (doneText != null &&
            (doneText.contains("打卡成功") || punchClicks > 0 || exists(root, listOf("打卡记录", "统计")))
        ) {
            root.safeRecycle()
            finish(true, if (testing) "页面显示已打卡，未重复点击" else "当前已是打卡状态")
            return
        }

        // 点完按钮至少等 3 轮看结果，别急着切去点「···」
        val punchSettled = punchClicks == 0 || ticks - lastPunchTick >= 3

        // 1) 菜单里的「进入应用」（预览页点开「···」后出现；有的版本直接给按钮）
        if (punchSettled && enterClicks < MAX_ENTER) {
            for (label in ENTER_LABELS) {
                val n = clickTarget(root, listOf(label))
                if (n == null) continue
                enterClicks++
                Prefs.log(this, "点击「$label」进入打卡应用")
                clickNode(n)
                enteredApp = true
                root.safeRecycle()
                handler.postDelayed(tickRunnable, 2800)
                return
            }
        }

        // 2) 右上角「···」。必须等入口点过、且至少两轮（给预览页加载时间），
        //    否则会在企微首页乱点顶栏的其它按钮。
        val loadedTick = lastEntryTick > 0 && ticks - lastEntryTick >= 2
        if (punchSettled && dotsClicks < MAX_DOTS && loadedTick) {
            val dots = findMoreButton(root)
            if (dots != null) {
                dotsClicks++
                Prefs.log(this, "点击右上角「更多」，展开应用菜单")
                val ok = clickNode(dots)
                if (!ok) tapTopRightMore(root)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, 2200)
                return
            }
            // 节点里摸不到（H5 自绘/胶囊按钮未暴露）：退化为手势点右上角区域
            if (dotsClicks == 0 && ticks - lastEntryTick >= 3) {
                dotsClicks++
                Prefs.log(this, "未识别到「更多」节点，改用点击右上角区域")
                tapTopRightMore(root)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, 2200)
                return
            }
        }

        // 3) 先切到工作台，再进打卡应用
        if (!wentWorkbench) {
            val tab = clickTarget(root, listOf("工作台"))
            if (tab != null) {
                Prefs.log(this, "切换到工作台")
                clickNode(tab)
                wentWorkbench = true
                root.safeRecycle()
                handler.postDelayed(tickRunnable, 1600)
                return
            }
        }

        // 4) 工作台上的打卡入口
        if (entryClicks < MAX_ENTRY) {
            for (label in listOf("考勤打卡", "打卡")) {
                val n = clickTarget(root, listOf(label))
                if (n == null) continue
                entryClicks++
                lastEntryTick = ticks
                Prefs.log(this, "点击打卡入口（$label，第 $entryClicks 次）")
                clickNode(n)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, 2600)
                return
            }
        }

        // 5) 首屏没看到打卡入口：向下翻页再找（实测企微工作台的打卡应用经常在第二屏之后）
        if (entryClicks == 0 && wentWorkbench && scrolls < MAX_SCROLLS && ticks - lastScrollTick >= 2) {
            if (scrollWorkbench(root)) {
                scrolls++
                lastScrollTick = ticks
                Prefs.log(this, "工作台未见打卡入口，向下翻第 $scrolls 页")
                root.safeRecycle()
                handler.postDelayed(tickRunnable, 1700)
                return
            }
        }

        // 6) 预算全部耗尽还没结果：明确失败，不无限重试
        if (punchClicks >= MAX_PUNCH && dotsClicks >= MAX_DOTS && enterClicks >= MAX_ENTER) {
            root.safeRecycle()
            finish(false, "已点击打卡按钮 $punchClicks 次但未出现完成提示（可能需拍照/人脸或位置校验）")
            return
        }

        root.safeRecycle()
        handler.postDelayed(tickRunnable, TICK)
    }

    /**
     * 点过「确认打卡」后的收尾。到这里工具已经没有可点的东西了：外勤确认弹窗是最后一步，
     * 剩下的定位/拍照/人脸由企微自己完成。三种结局都不再报「未完成 / 请手动补一下」：
     *   1) 读到完成文案、或完成状态已写入 → 明确成功；
     *   2) 企微不在前台（页面收起 / 自动息屏）→ 视为已提交，静默收尾；
     *   3) 等满 CONFIRM_WAIT_TICKS 仍无结果 → 视为已提交，静默收尾。
     */
    private fun handleAfterConfirm(root: AccessibilityNodeInfo?) {
        val pkg = root?.packageName?.toString()
        val inWework = pkg == WEWORK

        if (inWework) {
            val dt = firstSuccessText(root!!)
            if (dt != null) {
                root.safeRecycle()
                finish(true, "打卡成功（识别文案=$dt）")
                return
            }
        }
        root.safeRecycle()

        if (!testing && Prefs.isDone(this, slot)) {
            finish(true, "检测到今日$slot 已完成")
            return
        }

        if (!inWework) {
            val why = if (pkg == null) "页面已收起/已息屏" else "前台=$pkg"
            finishSubmitted("已提交「确认打卡」，企微不在前台（$why），结果以企微为准")
            return
        }

        if (ticks - confirmTick >= CONFIRM_WAIT_TICKS) {
            finishSubmitted("已提交「确认打卡」，未读到完成文案，结果请在企微确认")
            return
        }

        handler.postDelayed(tickRunnable, TICK)
    }

    private fun firstSuccessText(root: AccessibilityNodeInfo): String? {
        for (t in SUCCESS_TEXTS) {
            if (exists(root, listOf(t))) return t
        }
        return null
    }

    /** 优先用节点自带的滚动动作（省电、稳），没有再退化为手势上滑 */
    private fun scrollWorkbench(root: AccessibilityNodeInfo): Boolean {
        val scroller = findScrollable(root, 0)
        if (scroller != null) {
            val ok = try {
                scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } catch (e: Exception) {
                Prefs.log(this, "滚动节点异常：" + e.message)
                false
            } finally {
                scroller.safeRecycle()
            }
            if (ok) return true
        }
        return swipeUp(root)
    }

    private fun findScrollable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 16) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val hit = findScrollable(c, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    private fun swipeUp(root: AccessibilityNodeInfo): Boolean = try {
        val win = windowBounds(root)
        val cx = win.centerX().toFloat()
        val path = Path().apply {
            moveTo(cx, win.top + win.height() * 0.70f)
            lineTo(cx, win.top + win.height() * 0.35f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    } catch (e: Exception) {
        Prefs.log(this, "上滑手势失败：" + e.message)
        false
    }

    /** 已进入打卡应用（点过「进入应用」）：点「上班打卡/下班打卡」，然后等结果 */
    private fun handlePunchPage(root: AccessibilityNodeInfo) {
        if (punchClicks < MAX_PUNCH) {
            // 顺序：确认弹窗 → 本时段按钮 → 外出打卡 → 通用「打卡」。
            // 必须按本时段优先，否则上班栏的「已打卡」会被误判成完成，导致下班卡漏打。
            for (label in listOf("确认打卡", slot + "打卡", "外出打卡", "打卡")) {
                val btn = clickTarget(root, listOf(label))
                if (btn == null) continue
                punchClicks++
                if (label == "确认打卡") confirmTick = ticks
                Prefs.log(this, "点击打卡按钮（$label，第 $punchClicks 次）")
                clickNode(btn)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, if (punchClicks == 1) 2600 else 2000)
                return
            }
        }

        // 找不到可点的打卡按钮，才用完成文案判定（两个时段都打完的典型情况）
        if (confirmDoneOnPage(root)) {
            root.safeRecycle()
            finish(true, if (testing) "页面显示已打卡，未重复点击" else "当前已是打卡状态")
            return
        }

        // 点满次数后按钮还在、也没出现完成提示：多半需要拍照/人脸/位置校验
        if (punchClicks >= MAX_PUNCH) {
            root.safeRecycle()
            finish(false, "已点击打卡按钮 $punchClicks 次但未出现完成提示（可能需拍照/人脸或位置校验）")
            return
        }

        // 页面还在加载（白屏/骨架），继续等
        root.safeRecycle()
        handler.postDelayed(tickRunnable, TICK)
    }

    /**
     * 「已打卡」是否可信。预览页/历史页也会出现「已打卡」，
     * 所以除「打卡成功」这种即时文案外，都要求页面带今日标识（M月d日 或 当前 HH:mm）。
     */
    private fun confirmDoneOnPage(root: AccessibilityNodeInfo): Boolean {
        val doneText = firstDoneText(root) ?: return false
        if (doneText.contains("打卡成功")) return true
        if (punchClicks > 0) return true
        return exists(root, listOf(todayCn())) || exists(root, listOf(nowHm()))
    }

    /**
     * 取当前企微窗口的边界。
     * 折叠只用外屏时是整屏单页，展开时可能是左右分屏——两种情况下窗口宽高不同，
     * 所以位置判断一律以窗口实际 bounds 为准，不能用 resources.displayMetrics
     * （那是整块屏幕的尺寸，分屏时会把半边屏当成整个宽度）。
     */
    private fun windowBounds(root: AccessibilityNodeInfo): Rect {
        val r = Rect()
        root.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) {
            r.set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        return r
    }

    /**
     * 找右上角的「···」。findAccessibilityNodeInfosByText 是包含匹配，
     * 会把正文里的「更多」也捞出来，因此再用位置过滤：只认本窗口顶栏右侧区域。
     */
    private fun findMoreButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val win = windowBounds(root)
        val topLimit = win.top + dp(120)
        val rightZone = win.left + win.width() * 6 / 10
        for (q in DOTS_LABELS) {
            val list = root.findAccessibilityNodeInfosByText(q) ?: continue
            for (n in list) {
                val label = (n.contentDescription?.toString() ?: n.text?.toString() ?: "").trim()
                if (!isDotsLabel(label)) {
                    n.safeRecycle()
                    continue
                }
                val r = Rect()
                n.getBoundsInScreen(r)
                val inTopRight = r.centerY() <= topLimit && r.centerX() >= rightZone
                if (!inTopRight) {
                    n.safeRecycle()
                    continue
                }
                val target = selfOrClickableAncestor(n, 5)
                if (target != null) {
                    if (target !== n) n.safeRecycle()
                    return target
                }
                n.safeRecycle()
            }
        }
        return null
    }

    private fun isDotsLabel(s: String): Boolean {
        val core = s.replace(" ", "")
        if (core.isEmpty()) return false
        if (core.startsWith("更多")) return true
        return core == "···" || core == "..." || core == "⋯" || core == "•••" || core == "菜单"
    }

    /**
     * 兜底手势：企微小程序胶囊按钮通常「···」在关闭按钮左侧，
     * 因此取顶栏右侧可点元素里右边第二靠左那个；实在找不到就按固定比例点。
     */
    private fun tapTopRightMore(root: AccessibilityNodeInfo) {
        val win = windowBounds(root)
        val topLimit = win.top + dp(120)
        val rects = ArrayList<Rect>()
        collectTopRightClickables(root, topLimit, win.left + win.width() * 55 / 100, rects, 0)
        rects.sortByDescending { it.right }
        val target = when {
            rects.size >= 2 -> rects[1]
            rects.size == 1 -> rects[0]
            else -> Rect(win.right - dp(96), win.top + dp(24), win.right - dp(60), win.top + dp(48))
        }
        Prefs.log(this, "手势点击右上角 (" + target.centerX() + "," + target.centerY() + ")")
        tapPoint(target.centerX(), target.centerY())
    }

    private fun collectTopRightClickables(
        n: AccessibilityNodeInfo,
        topLimit: Int,
        minCenterX: Int,
        out: MutableList<Rect>,
        depth: Int
    ) {
        if (depth > 18 || out.size > 12) return
        if (n.isClickable) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0 && r.top < topLimit && r.centerX() >= minCenterX) {
                out.add(r)
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collectTopRightClickables(c, topLimit, minCenterX, out, depth + 1)
        }
    }

    private fun tapPoint(x: Int, y: Int): Boolean = try {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gd = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gd, null, null)
    } catch (e: Exception) {
        Prefs.log(this, "手势点击失败：" + e.message)
        false
    }

    private fun finish(ok: Boolean, msg: String) {
        val wasTest = testing
        running = false
        testing = false
        // 省电-4：成功/失败/超时所有分支统一清理 Handler，杜绝残留 Runnable
        handler.removeCallbacksAndMessages(null)

        // 测试通道：不写完成状态、不动生产 pending、通知带 [测试] 前缀。
        // 这样「立即测试」点多少次都不会影响当天正常打卡（原实现会写 markDone，
        // 测一次就把该时段标成已打，到点反被跳过）。
        val prefix = if (wasTest) "[测试] " else ""
        if (!wasTest) Prefs.clearPending(this)

        if (ok) {
            if (!wasTest) Prefs.markDone(this, slot)
            Prefs.log(this, prefix + "完成：" + msg)
            Notifier.alert(this, prefix + "打卡完成（" + slot + "）", msg)
        } else {
            // 兜底：流程判失败但完成状态其实已经写入（用户手动打过、被动检测刚标上），
            // 就别再弹「请手动补一下」了，否则会出现「已经打卡成功还被催补卡」的误报。
            if (!wasTest && Prefs.isDone(this, slot)) {
                Prefs.log(this, "流程未走完但今日$slot 已完成，按完成收尾（原判定：$msg）")
                return
            }
            Prefs.log(this, prefix + "失败：" + msg)
            Notifier.alert(this, prefix + "打卡未完成（" + slot + "）", msg + "，请手动补一下")
        }
    }

    /**
     * 「已提交」收尾：确认按钮已经点下去，结果由企微决定，工具不替用户判定成败。
     * 静默记日志、清 pending，并写内部完成标记，防止到点/解锁时重复触发再点一次。
     * 这个 markDone 只是工具内部「别再重复点」的标记，不是考勤记录。
     */
    private fun finishSubmitted(msg: String, remind: Boolean = false) {
        val wasTest = testing
        running = false
        testing = false
        handler.removeCallbacksAndMessages(null)
        val prefix = if (wasTest) "[测试] " else ""
        if (!wasTest) {
            Prefs.clearPending(this)
            Prefs.markDone(this, slot)
            if (remind) Notifier.alert(this, "打卡已提交（" + slot + "）", msg)
        }
        Prefs.log(this, prefix + "已提交：" + msg)
    }

    /** 卡住时告诉用户停在哪一步，日志里直接能看出该补哪个环节 */
    private fun stageName(): String = when {
        confirmTick >= 0 -> "已点「确认打卡」，等待企微确认结果"
        punchClicks > 0 -> "点击上班/下班打卡按钮"
        enteredApp -> "点击上班/下班打卡按钮"
        entryClicks == 0 && !wentWorkbench -> "打开企微工作台"
        entryClicks == 0 -> "点击工作台上的打卡入口"
        dotsClicks == 0 -> "点击右上角「更多」"
        enterClicks == 0 -> "点击菜单里的「进入应用」"
        else -> "等待打卡页加载"
    }

    /** Bug-2：查询节点用完即回收（含提前 return 的分支） */
    private fun exists(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (t in texts) {
            val list = root.findAccessibilityNodeInfosByText(t) ?: continue
            var hit = false
            for (n in list) {
                val s = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                if (!hit && s.contains(t)) hit = true
                n.safeRecycle()
            }
            if (hit) return true
        }
        return false
    }

    /**
     * Bug-9：trim 后保持「完全相等」语义（不用 contains，避免误点打卡记录/统计）。
     * Bug-2：列表元素用完即回收；返回的节点交由调用方 performAction 后回收；
     * parent 链引用交系统管理（回收遍历中的父节点会导致后续访问失效，任务书认可的做法）。
     */
    private fun clickTarget(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val list = root.findAccessibilityNodeInfosByText(t) ?: continue
            for (n in list) {
                val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                if (label != t) {
                    n.safeRecycle()
                    continue
                }
                val target = selfOrClickableAncestor(n, 6)
                if (target != null) {
                    if (n !== target) n.safeRecycle()
                    return target
                }
                n.safeRecycle()
            }
        }
        return null
    }

    private fun selfOrClickableAncestor(n: AccessibilityNodeInfo, maxDepth: Int): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < maxDepth) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        return try {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Prefs.log(this, "点击异常：" + e.message)
            false
        } finally {
            n.safeRecycle()
        }
    }

    /** Bug-9：超时排查辅助——收集页面含「打卡」的节点文本（最多 10 条） */
    private fun dumpCandidates(root: AccessibilityNodeInfo): String {
        val list = root.findAccessibilityNodeInfosByText("打卡") ?: return "（无候选）"
        val sb = StringBuilder()
        for ((i, n) in list.withIndex()) {
            val s = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (s.isNotEmpty() && i < 10) sb.append('[').append(s).append("] ")
            n.safeRecycle()
        }
        return if (sb.isEmpty()) "（无候选文本）" else "候选：$sb"
    }

    /** 超时排查辅助：把顶栏的文本/描述打出来，便于确认「···」到底叫什么 */
    private fun dumpTopBar(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val win = windowBounds(root)
        sb.append("窗口=").append(win.width()).append('x').append(win.height()).append(' ')
        collectTopBarLabels(root, win.top + dp(120), sb, 0)
        return if (sb.length <= 24) "（顶栏无可读节点）" else "顶栏：" + sb.toString().take(220)
    }

    private fun collectTopBarLabels(n: AccessibilityNodeInfo, topLimit: Int, sb: StringBuilder, depth: Int) {
        if (depth > 14 || sb.length > 320) return
        val t = n.text?.toString()?.trim() ?: ""
        val d = n.contentDescription?.toString()?.trim() ?: ""
        if (t.isNotEmpty() || d.isNotEmpty()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.top < topLimit && r.width() > 0) {
                sb.append(if (t.isNotEmpty()) "「$t」" else "「$d」")
                if (d.isNotEmpty() && t.isNotEmpty()) sb.append('/').append(d)
                sb.append('@').append(r.centerX()).append(',').append(r.centerY()).append(' ')
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collectTopBarLabels(c, topLimit, sb, depth + 1)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }
}
