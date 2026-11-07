package com.zmw.autocheckin

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CheckInScheduler.ACTION_DUE) return

        // 先排下一次，避免本次流程异常导致任务停摆
        CheckInScheduler.schedule(ctx)

        // 二次校验工作日：非唤醒闹钟（RTC）会在设备休眠时顺延，
        // 若顺延跨过午夜或用户中途改了节假日规则，可能落在不该打卡的日子上。
        // 这里按「闹钟实际送达时刻」重算，不信任排定时的判断。
        if (!WorkDay.isWorkDay(ctx, Calendar.getInstance())) {
            Prefs.log(ctx, "闹钟触发：今日非工作日（" +
                    WorkDay.describe(ctx, Calendar.getInstance()) + "），跳过")
            return
        }

        val slot = SlotResolver.resolve(ctx)

        // 手动打卡已被检测到：静默跳过，不打扰
        if (Prefs.isDone(ctx, slot)) {
            Prefs.log(ctx, "闹钟触发：今日$slot 已打卡，跳过")
            return
        }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = pm.isInteractive
        val locked = KeyguardUtil.isLocked(ctx)

        Prefs.log(ctx, "闹钟触发 slot=$slot 亮屏=$interactive 锁屏=$locked")

        if (interactive && !locked && Prefs.autoClick(ctx)) {
            CheckInAccessibilityService.requestCheckIn(ctx, slot, true)
        } else {
            CheckInAccessibilityService.setPending(ctx, slot)
            Notifier.alert(ctx, "该打卡了（$slot）", "点这里打开企业微信；解锁后也会自动补执行", openWework = true)
        }
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
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
