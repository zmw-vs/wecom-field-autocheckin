package com.zmw.autocheckin

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Calendar

/** 顺延超过 12 小时的旧闹钟不再补打：那时段早已过期，补点只会造成错卡 */
private const val DUE_GRACE_MS = 12 * 60 * 60 * 1000L

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CheckInScheduler.ACTION_DUE) return
        // 必须在 schedule() 覆盖之前取出本次的排定信息
        val due = Prefs.nextDue(ctx)
        Prefs.clearNextDue(ctx)
        // 先排下一次，避免本次流程异常导致任务停摆
        CheckInScheduler.schedule(ctx)
        DueHandler.handle(ctx, due)
    }
}

/** 到点处理的唯一入口：闹钟正常送达、服务重连补漏都走这里 */
object DueHandler {

    fun handle(ctx: Context, due: Prefs.NextDue?) {
        val now = System.currentTimeMillis()
        // RTC 非唤醒闹钟在设备休眠时顺延送达。用排定日/排定时段还原，
        // 而不是按送达时刻重算：否则顺延到非工作日会被跳过，跨夜顺延还会打错时段。
        // 只认 12 小时内的顺延；再旧的闹钟时段早已过期
        val d = due?.takeIf { now >= it.at - 60_000L && now - it.at <= DUE_GRACE_MS }
        val basis = d?.at ?: now
        if (!WorkDay.isWorkDay(ctx, basis)) {
            Prefs.log(ctx, "闹钟触发：排定日非工作日（" +
                    WorkDay.describe(ctx, Calendar.getInstance().apply { timeInMillis = basis }) +
                    "），跳过")
            return
        }

        val slot = d?.slot ?: SlotResolver.resolve(ctx)
        if (Prefs.isDone(ctx, slot)) {
            Prefs.log(ctx, "闹钟触发：今日$slot 已打卡，跳过")
            return
        }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = pm.isInteractive
        val locked = KeyguardUtil.isLocked(ctx)
        val delay = d?.let {
            if (now - it.at > 60_000L) "（RTC顺延 " + (now - it.at) / 1000 + "s）" else ""
        } ?: ""

        Prefs.log(ctx, "闹钟触发 slot=$slot 亮屏=$interactive 锁屏=$locked$delay")
        PendingRunner.dispatch(ctx, slot, interactive && !locked)
    }

    /**
     * 设备一直休眠时 RTC 闹钟不会投递，也就没写 pending；唤醒后系统重连无障碍服务，
     * schedule() 又会把那个「到期未投递」的闹钟 cancel 掉。这里在重排之前把到点未执行的
     * 排定补触发一次，是锁屏场景不漏卡的关键一步。
     */
    fun recoverMissed(ctx: Context) {
        val due = Prefs.nextDue(ctx) ?: return
        val now = System.currentTimeMillis()
        if (due.at > now - 30_000L || now - due.at > DUE_GRACE_MS) return
        Prefs.clearNextDue(ctx)
        Prefs.log(ctx, "发现到期未执行的排定，补触发：" + due.slot)
        handle(ctx, due)
    }
}

/** 系统解锁广播：静态注册，进程被省电策略杀掉后仍能被拉起 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        PendingRunner.tryResume(ctx, "解锁")
    }
}

/** 唤醒后补执行检查：显式 action 的静态接收器，RTC 闹钟在设备唤醒瞬间送达 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != CheckInScheduler.ACTION_RESUME) return
        PendingRunner.tryResume(ctx, "唤醒")
    }
}

/**
 * 每日定时打卡状态推送。
 * 三重前置校验：开关是否开启 / 今天是否已推过 / 到点才触发（由闹钟保证）。
 * 查询可能走网络，用 goAsync + 后台线程，避免阻塞主线程导致 ANR。
 */
class StatusPushReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != CheckInScheduler.ACTION_STATUS_DUE) return
        val app = ctx.applicationContext
        // 先排下一次，避免本次流程异常导致以后不再推
        CheckInScheduler.scheduleStatus(app)
        if (!Prefs.statusPushEnabled(app)) return
        if (Prefs.statusPushedToday(app)) {
            Prefs.log(app, "今日状态推送已完成，跳过重复触发")
            return
        }

        val pending = goAsync()
        Thread {
            try {
                StatusPush.send(app, StatusPush.build(app), true)
            } catch (e: Exception) {
                val why = e.message ?: e.javaClass.simpleName
                Prefs.log(app, "状态推送异常：" + why)
                StatusPush.send(
                    app,
                    StatusPush.Report(
                        "打卡状态未确认",
                        "状态查询异常（$why），请在企业微信查看当日打卡记录，勿据此判断已打卡/未打卡",
                        false
                    ),
                    true
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * 锁屏待办的统一入口。三重兜底，任何一条命中都能补执行：
 *   1) 解锁广播（静态 + 服务内动态各一份）；
 *   2) RTC 非唤醒闹钟，设备休眠不响、唤醒瞬间送达；
 *   3) 无障碍服务重连时 onServiceConnected 主动消费 pending。
 */
object PendingRunner {

    fun dispatch(ctx: Context, slot: String, screenReady: Boolean) {
        // 服务重连补漏与顺延闹钟可能在几秒内同时到达，只让第一次真正派发
        if (CheckInAccessibilityService.isRunning()) return
        if (Prefs.skipDispatch(ctx, slot)) {
            Prefs.log(ctx, "$slot 5 分钟内已派发，跳过重复触发")
            return
        }
        Prefs.markDispatch(ctx, slot)
        Prefs.setPending(ctx, slot)
        if (screenReady && Prefs.autoClick(ctx) && CheckInAccessibilityService.isReady()) {
            CheckInScheduler.cancelResume(ctx)
            CheckInAccessibilityService.requestCheckIn(ctx, slot, true)
        } else {
            Notifier.alert(ctx, "该打卡了（$slot）", "点这里打开企业微信；解锁后也会自动补执行", openWework = true)
            CheckInScheduler.scheduleResume(ctx)
        }
    }

    /** @return true 表示已成功发起打卡流程 */
    fun tryResume(ctx: Context, from: String): Boolean {
        if (!Prefs.enabled(ctx) || !Prefs.hasPending(ctx)) {
            CheckInScheduler.cancelResume(ctx)
            return false
        }
        // 解锁广播可能被静态 + 动态两个接收器各投一次，流程已在跑就直接返回
        if (CheckInAccessibilityService.isRunning()) {
            CheckInScheduler.cancelResume(ctx)
            return false
        }
        val slot = Prefs.pendingSlot(ctx)
        if (Prefs.isDone(ctx, slot)) {
            Prefs.clearPending(ctx)
            CheckInScheduler.cancelResume(ctx)
            Prefs.log(ctx, "$from 时发现今日$slot 已打卡，取消补执行")
            return false
        }
        if (Prefs.pendingAgeMs(ctx) > Prefs.PENDING_STALE_MS) {
            Prefs.clearPending(ctx)
            CheckInScheduler.cancelResume(ctx)
            Prefs.log(ctx, "锁屏待办已过期（超过2小时），丢弃")
            Notifier.alert(ctx, "打卡任务已超期", "锁屏待办超时未执行，请手动打卡", openWework = true)
            return false
        }
        if (!Prefs.autoClick(ctx)) {
            CheckInScheduler.cancelResume(ctx)
            return false
        }
        if (!isScreenReady(ctx)) {
            // 还锁着 / 屏幕没亮：继续等下一次唤醒检查
            CheckInScheduler.scheduleResume(ctx, 30_000L)
            return false
        }
        if (!CheckInAccessibilityService.isReady()) {
            // 进程刚被静态广播拉起，无障碍服务尚未重连：保留 pending，等重连或下一次检查
            CheckInScheduler.scheduleResume(ctx, 10_000L)
            return false
        }
        CheckInScheduler.cancelResume(ctx)
        Prefs.log(ctx, "$from：继续执行未完成的打卡 $slot")
        return CheckInAccessibilityService.requestCheckIn(ctx, slot, true)
    }

    private fun isScreenReady(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return false
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return !km.isKeyguardLocked
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DueHandler.recoverMissed(ctx)
            CheckInScheduler.schedule(ctx)
            Prefs.log(ctx, "开机/更新后重排定时")
        }
    }
}

object KeyguardUtil {
    fun isLocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }
}

object SlotResolver {

    /** 解析当前时刻所属时段，并输出日志（Bug-1 要求：slot 名称 + 当前时间） */
    fun resolve(ctx: Context): String {
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val slot = slotForMinute(ctx, cur)
        Prefs.log(ctx, "slot识别：$slot（当前 " +
                String.format("%02d:%02d", cur / 60, cur % 60) + "）")
        return slot
    }

    /**
     * Bug-1 修复：废弃 Math.abs 距离算法（午后打卡会被错误归属为「上班」，导致漏下班卡），
     * 改为时间区间中点判定；中点由 up/down 计算得出，不硬编码 12:00。
     * - 正常班（up <= down）：以 (up+down)/2 为中点，中点前算上班（含清晨提前打卡）、
     *   中点及之后算下班。
     * - 跨夜班（up > down，如 22:00 上班次日 06:00 下班）：以班次起点 up 归一化，
     *   班次前半段算上班、后半段算下班；班外时间归下班侧。
     */
    fun slotForMinute(ctx: Context, curMinute: Int): String {
        val up = Prefs.upMinutes(ctx)
        val down = Prefs.downMinutes(ctx)
        return if (up <= down) {
            val mid = (up + down) / 2
            if (curMinute < mid) "上班" else "下班"
        } else {
            val span = (down - up + 1440) % 1440
            val rel = (curMinute - up + 1440) % 1440
            if (rel * 2 < span) "上班" else "下班"
        }
    }
}
