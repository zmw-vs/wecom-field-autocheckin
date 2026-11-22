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

        // 提速：页面一变化就由无障碍事件驱动立刻续跑，固定延迟只作兜底轮询。
        // 总预算按真实时间算，不按轮数，避免事件密集时过早判超时。
        private const val TICK = 800L
        private const val MAX_TICK = 240
        private const val MAX_RUN_MS = 45_000L
        private const val FIRST_DELAY = 700L
        private const val AFTER_TAB = 650L
        private const val AFTER_ENTRY = 1100L
        private const val AFTER_DOTS = 800L
        private const val AFTER_ENTER = 1100L
        private const val AFTER_PUNCH_1 = 1000L
        private const val AFTER_PUNCH_N = 800L
        private const val AFTER_SCROLL = 700L
        private const val EVENT_TICK_DELAY = 160L
        private const val MIN_TICK_GAP_MS = 350L
        private const val ACTION_QUIET_MS = 450L
        private const val SETTLE_MS = 1500L
        private const val ENTRY_LOAD_MS = 900L

        /** 拉起企微后给系统的缓冲时间，超过这个时长企微进程还没起，判定被 MARs 终止 */
        private const val WEWORK_GONE_GRACE_MS = 6000L

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
         * 「明确打卡成功」的文案，只保留即时反馈类：
         * 「打卡成功 / 打卡完成」是企微打完立刻弹的，「更新打卡」是成功后才出现的按钮。
         *
         * 绝不能把「打卡时间」这类页面静态标签算成功——打卡页一直挂着这项，
         * 定位弱、压根没打上时它也在，正是「没打成却报成功」的根源。
         */
        private val STRONG_SUCCESS_TEXTS = listOf("打卡成功", "打卡完成", "更新打卡")

        /**
         * 弱证据：「已打卡 / 今日已打卡」在另一时段已打完、或历史记录页都会出现，
         * 单看它会把「上班已打卡」误当成本次「下班打卡」成功。
         * 只在本时段栏位旁边出现时才采信（见 slotAlreadyDone）。
         */
        private val WEAK_SUCCESS_TEXTS = listOf("已打卡", "今日已打卡", "打卡时间")

        /**
         * 打卡受阻：定位弱/无定位/网络差时企微会在打卡处显示这类文案，
         * 此时按钮点了也不会成功，必须判「未成功」，不能判成功。
         */
        private val BLOCKED_TEXTS = listOf(
            "定位失败", "无法定位", "定位异常", "定位不准", "定位精度低", "定位精度较低",
            "无法获取位置", "位置获取失败", "位置信息获取失败",
            "信号弱", "网络异常", "网络不给力", "请检查网络", "当前网络不可用"
        )

        /** 正在定位：既不是成功也不是失败，继续等，别急着下结论 */
        private val LOCATING_TEXTS = listOf("定位中", "正在定位", "正在获取定位", "获取定位中")

        /** 点完打卡按钮后，等这么久还没有明确结果就中性收尾，既不误报也不催补卡 */
        private const val PUNCH_VERDICT_MS = 4000L

        /** 企微停在内页（聊天/文档/邮件/设置）时按返回退回主界面，最多几次 */
        private const val MAX_BACK = 5
        private const val AFTER_BACK = 800L

        /** completionState 结果 */
        private const val ST_NONE = 0
        private const val ST_DONE = 1
        private const val ST_BLOCKED = 2

        /** 点完「确认打卡」最多再等 2.5 秒，够企微刷新结果，又不至于空转耗电 */
        private const val CONFIRM_WAIT_MS = 2500L
        private const val CONFIRM_WAIT_TICKS = 12

        /** 预览页菜单里的「进入应用」，不同企微版本叫法略有差异 */
        private val ENTER_LABELS = listOf("进入应用", "打开应用", "进入小程序", "打开小程序", "立即进入")

        /** 右上角三个点的常见无障碍描述 */
        private val DOTS_LABELS = listOf("更多", "更多选项", "更多操作", "更多功能", "更多菜单", "菜单", "···", "...", "⋯")

        // Bug-5：静态引用，onServiceConnected 赋值、onDestroy/onUnbind 置空，
        // 调用方一律先判空并 try-catch（进程被直接 kill 时 onDestroy 不会执行）
        @Volatile
        private var alive: CheckInAccessibilityService? = null

        fun isReady(): Boolean = alive != null

        /** 供 PendingRunner 去重：解锁广播可能被静态 + 动态接收器各投一次 */
        fun isRunning(): Boolean = alive?.running == true

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

    /** 提速用：按真实时间判断静默期/页面已加载，不受事件驱动下 tick 频率变化影响 */
    private var lastTickAt = 0L
    private var lastActionAt = 0L
    private var lastEntryAt = 0L
    private var lastPunchAt = 0L
    private var lastScrollAt = 0L
    private var confirmAt = 0L

    /** 仅执行期间动态开启内容变化事件，流程一结束立刻还原，平时零额外开销 */
    private var contentEventsOn = false

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

    /**
     * 企微被用户停在聊天/文档/邮件等内页时，底部标签栏不可见，够不到「工作台」。
     * 靠「按返回逐层退回主界面」解决，次数受限，不做重新拉起（会崩企微进程）。
     */
    private var backs = 0
    private var lastBackAt = 0L

    /** 点完打卡按钮、还没拿到明确结论时，用于「中性收尾」的超时基准 */
    private var awaitingVerdict = false

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
            // 统一走 PendingRunner：过期/已完成/开关/服务未就绪都由它判断，
            // 静态 UnlockReceiver 可能同时投递，isRunning() 会去重
            try {
                PendingRunner.tryResume(ctx, "解锁")
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
        // 设备休眠期间 RTC 闹钟不会投递，唤醒后这条「到期未执行」的排定要先补触发，
        // 否则下面的 schedule() 会把它 cancel 掉，锁屏那次打卡就彻底没了。
        try {
            DueHandler.recoverMissed(this)
        } catch (e: Exception) {
            Prefs.log(this, "补触发到期排定失败：" + e.message)
        }
        try {
            CheckInScheduler.schedule(this)
        } catch (e: Exception) {
            Prefs.log(this, "服务重连时重排定时失败：" + e.message)
        }
        // 锁屏期间进程可能被省电策略杀掉，系统重连无障碍后主动消费待办。
        // 这一步是关键：解锁广播可能早在 pending 写入前就发过了，不会再有第二次。
        try {
            PendingRunner.tryResume(this, "服务重连")
        } catch (e: Exception) {
            Prefs.log(this, "服务重连补执行失败：" + e.message)
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
        setContentEvents(false)
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
            setContentEvents(false)
            handler.removeCallbacksAndMessages(null)
            Prefs.log(this, "服务被中断，终止当前打卡流程")
        }
    }

    /**
     * 被动检测入口：用户手动打卡必经企微打卡页。
     * 省电-1：非执行期第一件事判断双时段完成，完成则零解析直接返回。
     * 省电-2：限频 1800ms；事件源由 config 的 packageNames 限定为企微。
     * 执行期改为事件驱动：页面一变化就立刻续跑，固定 TICK 只作兜底。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName?.toString() != WEWORK) return
        if (running) {
            // 点击后静默一小段，避免按钮的点击反馈事件把状态机推得过快；
            // 再由最小 tick 间隔兜住企微 H5 的高频刷新事件。
            val now = SystemClock.elapsedRealtime()
            if (now - lastActionAt < ACTION_QUIET_MS) return
            if (now - lastTickAt < MIN_TICK_GAP_MS) return
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, EVENT_TICK_DELAY)
            return
        }
        if (Prefs.isDone(this, "上班") && Prefs.isDone(this, "下班")) return
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
            // 手动打卡同样算一条打卡记录，和自动成功共用去重
            SuccessNotifier.onSuccess(this, s)
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

    /** 返回第一个命中的文案，用于日志里说明到底是被哪条拦下的 */
    private fun firstMatch(root: AccessibilityNodeInfo, texts: List<String>): String? {
        for (t in texts) {
            if (exists(root, listOf(t))) return t
        }
        return null
    }

    /**
     * 判断当前页面上的完成/受阻状态。
     *
     * 返回 ST_BLOCKED 时一定不是成功：定位弱/没定位/网络异常时企微会在打卡处显示提示，
     * 按钮点了也不会成功。之前把「打卡时间」当成功文案，这类场景直接误报成功。
     *
     * 返回 ST_DONE 需要证据，且证据分强弱：
     *   - STRONG：打卡成功/打卡完成/更新打卡，是打完才出现的即时反馈；
     *   - WEAK：已打卡/今日已打卡/打卡时间，打卡页常驻或另一时段已打也会出现，
     *     必须确认它落在**本时段**栏位附近才采信，避免「上班已打卡」骗过下班卡判定。
     */
    private fun completionState(root: AccessibilityNodeInfo): Int {
        // 受阻判定必须让位于「还能点」：
        // 打卡页在未打卡时也可能挂着「定位中/信号弱」之类的说明性文案，
        // 若不加限制，会在按钮明明可点的情况下直接判失败，等于把能打的卡放弃了。
        // 所以只有「点过按钮后仍受阻」或「压根没有可点按钮」时，才认为是真受阻。
        val blocked = firstMatch(root, BLOCKED_TEXTS)
        if (blocked != null) {
            val loc = firstMatch(root, LOCATING_TEXTS)
            if (loc != null && punchClicks == 0) {
                // 正在定位：再等等，别急着下结论也不急着点
                return ST_NONE
            }
            val canStillPunch = punchClicks < MAX_PUNCH &&
                clickTarget(root, listOf("确认打卡", slot + "打卡", "外出打卡"))
                    .also { it.safeRecycle() } != null
            if (!canStillPunch) return ST_BLOCKED
            // 按钮还在就先点，点完再看是否仍然受阻
            return ST_NONE
        }

        // 还没点过任何打卡按钮：只有「打卡成功/打卡完成」这类即时文案才算数，
        // 而且此时能看到它基本是上一次刚打完的残留，交给上层做「当前已是打卡状态」。
        // 弱证据（已打卡/打卡时间）在这里一律不采信 —— 打卡页的「上班打卡」按钮
        // 和「打卡时间」标签常常并排显示，按位置匹配会把「还没打」误判成「已打」，
        // 结果就是该打的卡被跳过，这是比误报成功更严重的错。
        if (punchClicks == 0) {
            return if (firstMatch(root, STRONG_SUCCESS_TEXTS) != null) ST_DONE else ST_NONE
        }

        // 点过按钮后：明确成功文案直接算成功
        if (firstMatch(root, STRONG_SUCCESS_TEXTS) != null) return ST_DONE

        // 弱证据必须绑定到本时段栏位才采信，避免「上班已打卡」骗过下班卡判定
        val weak = firstMatch(root, WEAK_SUCCESS_TEXTS) ?: return ST_NONE
        if (slotAlreadyDone(root)) {
            Prefs.log(this, "点过按钮后本时段栏位旁出现「$weak」，判定本时段已完成")
            return ST_DONE
        }
        return ST_NONE
    }

    /**
     * 「已打卡」是否属于**本时段**（slot）。
     * 打卡页左右/上下两个栏位各自显示状态，先定位本时段栏位的坐标，
     * 再看「已打卡」是否落在同一行附近。太宽松就会把上班已打卡当成本次下班成功。
     */
    private fun slotAlreadyDone(root: AccessibilityNodeInfo): Boolean {
        val slotRect = firstNodeRect(root, slot) ?: return false
        var hit = false
        for (t in WEAK_SUCCESS_TEXTS) {
            for (n in safeFindByText(root, t)) {
                if (!hit) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    // 同一栏位：垂直中心相差不超过 1.6 倍行高，且水平范围有重叠
                    val dy = Math.abs(r.centerY() - slotRect.centerY())
                    val sameRow = dy <= maxOf(r.height(), slotRect.height(), dp(40)) * 1.6f
                    val overlapX = r.left < slotRect.right && slotRect.left < r.right
                    if (sameRow && overlapX) hit = true
                }
                n.safeRecycle()
            }
        }
        return hit
    }

    private fun firstNodeRect(root: AccessibilityNodeInfo, text: String): Rect? {
        val list = safeFindByText(root, text)
        var best: Rect? = null
        for (n in list) {
            val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (label == text || label.startsWith(text)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    // 取最靠上的那个（打卡页栏位标题通常是第一个）
                    if (best == null || r.top < best.top) best = Rect(r)
                }
            }
            n.safeRecycle()
        }
        return best
    }

    private fun safeFindByText(
        root: AccessibilityNodeInfo,
        text: String
    ): List<AccessibilityNodeInfo> = try {
        root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 当前是否站在「能找到打卡入口」的界面。
     *
     * 只认企微**底部标签栏**里的那几项（消息/邮件/文档/工作台/通讯录/我）。
     * 注意不能把「文档」「邮件」「日程」单独当主界面标志——用户很可能正停在
     * 某个文档或邮件的详情页里，那些页面上也有同名文字，会误判成主界面而不再退back。
     *
     * 判据：标签栏项在屏幕底部区域，且同屏出现 ≥2 项。单个「消息」可能是聊天页标题，
     * 所以要求成组出现。
     */
    private fun atNavigableSurface(root: AccessibilityNodeInfo): Boolean {
        // 已经在打卡页/预览页/确认弹窗上：别按返回，否则会把要点的界面关掉
        if (punchClicks > 0) return true
        if (enteredApp) return true
        if (exists(root, listOf("确认打卡"))) return true
        if (exists(root, listOf("上班打卡", "下班打卡", "外出打卡"))) return true
        if (exists(root, listOf("进入应用", "打开应用", "进入小程序", "打开小程序"))) return true
        if (exists(root, listOf("进入应用", "立即进入"))) return true

        // 底部标签栏：仅统计落在屏幕下方 20% 内的标签文字
        val win = windowBounds(root)
        val bottomTop = win.top + (win.height() * 0.80f).toInt()
        val tabNames = listOf("消息", "邮件", "文档", "工作台", "通讯录", "我")
        var hits = 0
        for (t in tabNames) {
            val list = safeFindByText(root, t)
            var matched = false
            for (n in list) {
                val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                if (label == t) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.height() > 0 && r.centerY() >= bottomTop) matched = true
                }
                n.safeRecycle()
            }
            if (matched) hits++
        }
        // 成组出现才认（单个可能是正文里的字），≥2 项足以排除详情页
        return hits >= 2
    }

    /**
     * 视觉上是否明显是企微的「内页」（聊天会话、文档正文、邮件详情等）。
     * 只用于日志和「要不要退」的判断依据；不含设置页这类容易与主界面混淆的特征。
     */
    private fun looksInnerPage(root: AccessibilityNodeInfo): Boolean {
        // 聊天会话页特征
        if (exists(root, listOf("发送消息", "发送给", "输入消息"))) return true
        // 文档/邮件/日程详情
        if (exists(root, listOf("相关讨论", "分享给同事", "添加到日程"))) return true
        return false
    }

    fun begin(slotName: String, isTest: Boolean = false): Boolean {
        if (running) {
            // watchdog：正常流程最长 45s 必然 finish，超过 60s 还挂着说明异常中断了，
            // 强制重置，否则「立即测试」会被永久锁死
            if (SystemClock.elapsedRealtime() - beginAt > 60_000L) {
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
        backs = 0
        lastBackAt = 0L
        awaitingVerdict = false
        lastPunchTick = 0
        confirmTick = -1
        wentWorkbench = false
        lastTickAt = 0L
        lastActionAt = SystemClock.elapsedRealtime()
        lastEntryAt = 0L
        lastPunchAt = 0L
        lastScrollAt = 0L
        confirmAt = 0L
        slot = slotName

        // 只在执行的这十几秒里开启内容变化事件，页面内按钮一出现就能立刻续跑；
        // finish 时一定还原，平时仍是原来的最小事件面。
        setContentEvents(true)

        Prefs.log(this, (if (isTest) "[测试] 开始自动打卡：" else "开始自动打卡：") + slot)
        launchWework()
        handler.postDelayed(tickRunnable, FIRST_DELAY)
        return true
    }

    /**
     * 把企微拉到前台。**企微已经在前台时绝不调用 startActivity。**
     *
     * 三星 MARs（自动运行管理）的坑（11-22 实机抓日志确认）：
     *
     *   19:01:15.527 FASProvider: package_name=com.zmw.autocheckin
     *                reason=deleted_from_mars_auto mode=1 level=2
     *   19:01:17.907 ActivityManager: Force stopping com.tencent.wework: MARs #2
     *   19:01:17.931 MARsPolicyManager: Recent_Kill: add mRestrictedPackages
     *                com.tencent.wework policy --autorun(2)
     *
     * 本应用被三星判成「自动运行可疑应用」（deleted_from_mars_auto，level=2）。
     * 它一旦 startActivity 拉起企微，MARs 就认定企微是被可疑应用自动启动的，
     * 直接 Force stop 掉整个企微进程并加入受限包。用户看到的就是「企微崩溃、
     * 打不开」——而且连手动点图标都恢复不了，因为企微已被限制。
     *
     * 实测对照：
     *   - 企微已在前台，只发 GLOBAL_ACTION_BACK：pid 不变，MARs 不动 ✅
     *   - 企微已在前台，仍 startActivity：pid 立刻消失，MARs Force stop ❌
     *
     * 所以分两条路：
     *   1) 企微已在前台 → 一次都不拉起，纯靠无障碍点 + 返回键导航。
     *   2) 企微不在前台 → 才需要拉起，这是唯一会碰 MARs 的路径，留给系统
     *      冷启动，不加任何 CLEAR_TOP（那会重跑闪屏，是更早一版的崩溃原因）。
     */
    private fun launchWework(): Boolean {
        // 企微已经在最前台：什么都不做，直接开始 tick。
        // 这是最关键的一条——实测「已在前台仍拉起」必被 MARs 杀掉企微。
        val cur = rootInActiveWindow
        val curPkg = cur?.packageName?.toString()
        cur.safeRecycle()
        if (curPkg == WEWORK) {
            Prefs.log(this, "企微已在前台，跳过拉起（避免触发三星 MARs 自动运行拦截）")
            return true
        }

        val launch = packageManager.getLaunchIntentForPackage(WEWORK)
        if (launch == null) {
            finish(false, "未安装企业微信")
            return false
        }
        // 只做「置前」，不用 CLEAR_TOP/CLEAR_TASK：那个 intent 指向企微的启动
        // 闪屏 Activity，清栈会让它重跑冷启动初始化，踩到已存在的进程级单例。
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        return try {
            startActivity(launch)
            Prefs.log(this, "企微不在前台，已拉起（注意：三星设备可能触发 MARs 拦截）")
            true
        } catch (e: Exception) {
            finish(false, "启动企业微信失败：" + e.message)
            false
        }
    }

    /**
     * 动态增删 TYPE_WINDOW_CONTENT_CHANGED。企微打卡页是 H5，按钮出现时不一定伴随
     * 窗口状态变化；执行期加上它才能做到「页面就绪即点」。流程结束立刻还原，
     * 空闲时不会额外收到任何事件，保持省电。
     */
    private fun setContentEvents(on: Boolean) {
        if (contentEventsOn == on) return
        try {
            val info = serviceInfo ?: return
            val base = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            info.eventTypes = if (on) base or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else base
            serviceInfo = info
            contentEventsOn = on
        } catch (e: Exception) {
            Prefs.log(this, "调整事件监听失败：" + e.message)
        }
    }

    /**
     * 企微是否还「在系统里活着」。
     *
     * **不能用 ActivityManager.getRunningAppProcesses()**：Android 11+ 起它只返回
     * 调用者自己那一个进程，查别的包永远是空，会一直被误判成「企微没了」
     * （11-22 实测：企微 pid 29898 明明活着，却被判成被 MARs 终止）。
     *
     * 只走 /proc 扫描：能读到 cmdline 且包含包名 = 活着。判定规则刻意做得极保守：
     *   - /proc 列不出东西，或读到的进程数太少（<20）→ 说明权限被限制，无法判断，
     *     一律当「活着」，宁可多等 6 秒也不要误报失败；
     *   - 只有「明确读到几十个进程、逐个看完确实没有企微」才返回 false。
     *
     * 这个函数只在「企微不在前台」的分支里用，误判代价是弹一条错误的失败提示，
     * 所以保守优先。
     */
    private fun isWeworkAlive(): Boolean {
        return try {
            val pids = java.io.File("/proc").list() ?: return true
            var readable = 0
            for (name in pids) {
                if (name.isEmpty() || name[0] < '0' || name[0] > '9') continue
                val f = java.io.File("/proc/$name/cmdline")
                if (!f.canRead()) continue
                readable++
                val cmd = try {
                    f.readText()
                } catch (_: Exception) {
                    continue
                }
                if (cmd.contains(WEWORK)) return true
            }
            // 读得太少说明 /proc 被系统屏蔽，判断不了 → 当活着
            if (readable < 20) return true
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun tick() {
        if (!running) return
        val nowRt = SystemClock.elapsedRealtime()
        // 刚点过 / 刚滑过：给页面一点渲染时间，不立刻重复扫同一帧
        val quietLeft = ACTION_QUIET_MS - (nowRt - lastActionAt)
        if (quietLeft > 0L) {
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, quietLeft)
            return
        }
        lastTickAt = nowRt
        ticks++
        if (ticks > MAX_TICK || nowRt - beginAt > MAX_RUN_MS) {
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
            if (punchClicks > 0 && nowRt - lastPunchAt >= SETTLE_MS) {
                finishSubmitted(
                    "已点打卡按钮（$slot），企微页面已收起，结果以企微为准",
                    remind = true
                )
                return
            }
            // 还没点到打卡按钮，企微进程却没了：三星 MARs（自动运行管理）把企微
            // Force stop 掉的典型表现——用户看到的就是「企微崩溃、打不开」。
            // 与其空转到 MAX_TICK 超时只说「未完成」，不如直接点明原因和解决办法。
            if (punchClicks == 0 && !isWeworkAlive() && nowRt - beginAt > WEWORK_GONE_GRACE_MS) {
                Prefs.log(this, "企微进程已不存在，判定为被系统（MARs）终止")
                finish(
                    false,
                    "企业微信被系统终止了（三星「自动运行管理」拦截了本应用拉起企微）。" +
                        "解决办法：设置 → 电池和设备维护 → 电池 → 后台使用限制，" +
                        "把「外勤打卡助手」和「企业微信」都设为「不受限制」，再重试"
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

        // 0) 先确认自己站在能找到打卡入口的地方。
        //    企微被打开过、停在聊天/文档/邮件等内页时，底部标签栏不可见，
        //    下面所有「工作台 / 打卡」的匹配都会落空，只能空转到超时。
        //    正确做法：用返回键逐层退回主界面（跟用户手按返回完全一样，安全）。
        //    绝不重新拉起带 CLEAR_TOP —— 那会重建企微闪屏并崩掉它的进程。
        if (!atNavigableSurface(root)) {
            if (punchClicks == 0 && nowRt - lastBackAt >= AFTER_BACK) {
                val inner = looksInnerPage(root)
                if (backs < MAX_BACK) {
                    backs++
                    lastBackAt = nowRt
                    lastActionAt = nowRt
                    Prefs.log(this, "企微不在可导航界面（内页=" + inner + "），按返回退回主界面（第 $backs 次）")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    root.safeRecycle()
                    handler.postDelayed(tickRunnable, AFTER_BACK)
                    return
                }
                // 连按返回都回不到主界面：别再折腾企微了。
                // 之前的「重新拉起」在 CLEAR_TOP 时代是崩溃元凶；现在即便不带 flag，
                // 反复拉起一个自身状态异常的企微也可能加重问题，所以这里直接收尾报失败，
                // 把处置权交还用户，比冒险把对方进程搞崩要好。
                Prefs.log(this, "按返回 $backs 次仍未回到企微主界面，放弃本次自动打卡")
                root.safeRecycle()
                finish(
                    false,
                    "企微停在非常规页面，按返回 $backs 次未回到主界面，请手动打开企业微信后重试"
                )
                return
            }
        }

        // 0) 打卡按钮已在眼前：直接点。确认弹窗（不在打卡范围内 → 确认打卡）优先级最高
        if (punchClicks < MAX_PUNCH) {
            for (label in listOf("确认打卡", slot + "打卡", "外出打卡")) {
                val btn = clickTarget(root, listOf(label))
                if (btn == null) continue
                punchClicks++
                lastPunchTick = ticks
                lastPunchAt = SystemClock.elapsedRealtime()
                lastActionAt = lastPunchAt
                awaitingVerdict = true
                if (label == "确认打卡") {
                    confirmTick = ticks
                    confirmAt = lastPunchAt
                }
                Prefs.log(this, "点击打卡按钮（$label，第 $punchClicks 次）")
                clickNode(btn)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, if (punchClicks == 1) AFTER_PUNCH_1 else AFTER_PUNCH_N)
                return
            }
        }

        // 0.5) 成功/受阻判定。这里是「误报成功」的第二道闸：
        //   a) 明确受阻（定位弱/无定位/网络异常）→ 本次一定不成功，交给中性收尾；
        //   b) 明确成功文案只在真的点过按钮后才采信（避免进门就被历史「已打卡」骗过）；
        //   c) 弱证据「已打卡/打卡时间」必须落在本时段栏位旁，才认为是本时段已完成。
        val verdict = completionState(root)
        if (verdict == ST_BLOCKED) {
            val why = firstMatch(root, BLOCKED_TEXTS) ?: "定位/网络异常"
            root.safeRecycle()
            if (punchClicks > 0) {
                // 已经点过按钮但被拦住：不是成功，也不能催「补卡」——人已经在流程里了
                finishBlocked(why)
            } else {
                // 还没点就发现环境不满足，点了也白点，直接提示用户本人处理
                finish(false, "打卡受阻（$why），当前环境无法完成打卡，请手动处理")
            }
            return
        }
        if (verdict == ST_DONE) {
            val strong = firstMatch(root, STRONG_SUCCESS_TEXTS)
            root.safeRecycle()
            if (strong != null) {
                if (punchClicks > 0) {
                    finish(true, "打卡成功（识别文案=$strong）")
                } else {
                    finish(true, if (testing) "页面显示已打卡，未重复点击" else "当前已是打卡状态")
                }
                return
            }
            // 弱证据命中：本时段确实已完成，不再重复点
            finish(true, if (testing) "页面显示已打卡，未重复点击" else "当前已是打卡状态")
            return
        }

        // 点完按钮至少等 1.5 秒看结果，别急着切去点「···」
        val punchSettled = punchClicks == 0 || nowRt - lastPunchAt >= SETTLE_MS

        // 1) 菜单里的「进入应用」（预览页点开「···」后出现；有的版本直接给按钮）
        if (punchSettled && enterClicks < MAX_ENTER) {
            for (label in ENTER_LABELS) {
                val n = clickTarget(root, listOf(label))
                if (n == null) continue
                enterClicks++
                lastActionAt = SystemClock.elapsedRealtime()
                Prefs.log(this, "点击「$label」进入打卡应用")
                clickNode(n)
                enteredApp = true
                root.safeRecycle()
                handler.postDelayed(tickRunnable, AFTER_ENTER)
                return
            }
        }

        // 2) 右上角「···」。必须等入口点过、且页面至少渲染了 0.9 秒，
        //    否则会在企微首页乱点顶栏的其它按钮。
        val loadedTick = lastEntryAt > 0 && nowRt - lastEntryAt >= ENTRY_LOAD_MS
        if (punchSettled && dotsClicks < MAX_DOTS && loadedTick) {
            val dots = findMoreButton(root)
            if (dots != null) {
                dotsClicks++
                lastActionAt = SystemClock.elapsedRealtime()
                Prefs.log(this, "点击右上角「更多」，展开应用菜单")
                val ok = clickNode(dots)
                if (!ok) tapTopRightMore(root)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, AFTER_DOTS)
                return
            }
            // 节点里摸不到（H5 自绘/胶囊按钮未暴露）：退化为手势点右上角区域
            if (dotsClicks == 0 && nowRt - lastEntryAt >= ENTRY_LOAD_MS + 600L) {
                dotsClicks++
                lastActionAt = SystemClock.elapsedRealtime()
                Prefs.log(this, "未识别到「更多」节点，改用点击右上角区域")
                tapTopRightMore(root)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, AFTER_DOTS)
                return
            }
        }

        // 3) 先切到工作台，再进打卡应用
        if (!wentWorkbench) {
            val tab = clickTarget(root, listOf("工作台"))
            if (tab != null) {
                lastActionAt = SystemClock.elapsedRealtime()
                Prefs.log(this, "切换到工作台")
                clickNode(tab)
                wentWorkbench = true
                root.safeRecycle()
                handler.postDelayed(tickRunnable, AFTER_TAB)
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
                lastEntryAt = SystemClock.elapsedRealtime()
                lastActionAt = lastEntryAt
                Prefs.log(this, "点击打卡入口（$label，第 $entryClicks 次）")
                clickNode(n)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, AFTER_ENTRY)
                return
            }
        }

        // 5) 首屏没看到打卡入口：向下翻页再找（实测企微工作台的打卡应用经常在第二屏之后）
        if (entryClicks == 0 && wentWorkbench && scrolls < MAX_SCROLLS &&
            nowRt - lastScrollAt >= 700L
        ) {
            if (scrollWorkbench(root)) {
                scrolls++
                lastScrollTick = ticks
                lastScrollAt = SystemClock.elapsedRealtime()
                lastActionAt = lastScrollAt
                Prefs.log(this, "工作台未见打卡入口，向下翻第 $scrolls 页")
                root.safeRecycle()
                handler.postDelayed(tickRunnable, AFTER_SCROLL)
                return
            }
        }

        // 6) 点过打卡按钮但迟迟没有明确结果（既不是成功文案，也不是受阻提示）。
        //    常见于企微还在转圈校验定位。等满 PUNCH_VERDICT_MS 就中性收尾：
        //    不判成功（可能没打上）、也不弹「请手动补一下」（人就在流程里，别催）。
        if (awaitingVerdict && punchClicks > 0 && nowRt - lastPunchAt >= PUNCH_VERDICT_MS) {
            root.safeRecycle()
            finishSubmitted(
                "已点击打卡按钮（$slot），未读到明确结果，请在企业微信确认",
                remind = true
            )
            return
        }

        // 7) 预算全部耗尽还没结果：明确失败，不无限重试
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
            // 受阻优先：定位弱/网络异常时「确认打卡」点下去也不会成功，
            // 不能因为页面上还有「打卡时间」这类常驻文案就判成功
            val blocked = firstMatch(root!!, BLOCKED_TEXTS)
            if (blocked != null) {
                root.safeRecycle()
                finishBlocked(blocked)
                return
            }
            val dt = firstSuccessText(root)
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

        if (SystemClock.elapsedRealtime() - confirmAt >= CONFIRM_WAIT_MS ||
            ticks - confirmTick >= CONFIRM_WAIT_TICKS
        ) {
            finishSubmitted("已提交「确认打卡」，未读到完成文案，结果请在企微确认")
            return
        }

        handler.postDelayed(tickRunnable, TICK)
    }

    private fun firstSuccessText(root: AccessibilityNodeInfo): String? {
        // 只认强证据：「打卡时间」是页面常驻标签，留着就会把「没打成」判成「打成」
        for (t in STRONG_SUCCESS_TEXTS) {
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
                lastPunchAt = SystemClock.elapsedRealtime()
                lastActionAt = lastPunchAt
                if (label == "确认打卡") {
                    confirmTick = ticks
                    confirmAt = lastPunchAt
                }
                Prefs.log(this, "点击打卡按钮（$label，第 $punchClicks 次）")
                clickNode(btn)
                root.safeRecycle()
                handler.postDelayed(tickRunnable, if (punchClicks == 1) AFTER_PUNCH_1 else AFTER_PUNCH_N)
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

    /**
     * 打卡受阻收尾：定位弱 / 无定位 / 网络异常等，企微明确提示打不了。
     *
     * 关键点：这类情况**绝不能判成功**（以前把「打卡时间」当成功文案，就是在这里误报的），
     * 同时也**不写完成标记**——写了会导致今天不会再重试，白白漏卡。
     * 保留 pending 让解锁/唤醒还能再试一次，并用中性文案提醒用户本人处理。
     */
    private fun finishBlocked(why: String) {
        val wasTest = testing
        running = false
        testing = false
        awaitingVerdict = false
        setContentEvents(false)
        handler.removeCallbacksAndMessages(null)
        val prefix = if (wasTest) "[测试] " else ""
        Prefs.log(this, prefix + "打卡受阻（" + why + "），不判定成功、不写完成状态")

        if (!wasTest) {
            // 不 clearPending、不 markDone：环境恢复后（走到信号好的地方）还能自动补打
            Notifier.alert(
                this,
                "打卡未成功（" + slot + "）",
                "企业微信提示「" + why + "」，多半是定位或网络不给力。" +
                    "请走到信号好的地方手动打卡，或在企业微信确认结果",
                openWework = true
            )
        } else {
            Notifier.alert(this, prefix + "打卡受阻（" + slot + "）", "识别到「" + why + "」，未完成打卡")
        }
    }

    private fun finish(ok: Boolean, msg: String) {
        val wasTest = testing
        running = false
        testing = false
        setContentEvents(false)
        CheckInScheduler.cancelResume(this)
        // 省电-4：成功/失败/超时所有分支统一清理 Handler，杜绝残留 Runnable
        handler.removeCallbacksAndMessages(null)

        // 测试通道：不写完成状态、不动生产 pending、通知带 [测试] 前缀。
        // 这样「立即测试」点多少次都不会影响当天正常打卡（原实现会写 markDone，
        // 测一次就把该时段标成已打，到点反被跳过）。
        val prefix = if (wasTest) "[测试] " else ""
        if (!wasTest) Prefs.clearPending(this)

        if (ok) {
            if (!wasTest) {
                Prefs.markDone(this, slot)
                // 唯一一条「打卡成功」通知，按日期+时段去重；
                // 原先的「打卡完成」提醒由此替换，避免同一次打卡弹两条。
                SuccessNotifier.onSuccess(this, slot)
            } else {
                Notifier.alert(this, prefix + "打卡完成（" + slot + "）", msg)
            }
            Prefs.log(this, prefix + "完成：" + msg)
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
        setContentEvents(false)
        CheckInScheduler.cancelResume(this)
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
