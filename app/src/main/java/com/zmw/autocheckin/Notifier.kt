package com.zmw.autocheckin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {

    private const val CHANNEL = "checkin_alert"
    const val ID = 2001
    const val ID_SUCCESS = 2002
    const val ID_STATUS = 2003

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "打卡提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点提醒与打卡结果"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun alert(ctx: Context, title: String, text: String, openWework: Boolean = false, id: Int = ID) {
        ensureChannel(ctx)

        // 点击通知直达企业微信（打不开则回退到助手页面）
        val weworkIntent = if (openWework) {
            ctx.packageManager.getLaunchIntentForPackage("com.tencent.wework")
        } else null
        val intent = weworkIntent
            ?: Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            Prefs.log(ctx, "通知权限未授予,提醒未能展示")
        }
    }

    /** 打卡成功即时通知：独立 ID，不顶掉「该打卡了」和每日状态推送 */
    fun success(ctx: Context, slot: String) {
        val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        alert(
            ctx, "打卡成功",
            "今日${slot}卡已完成 · $hm（结果以企业微信为准）",
            id = ID_SUCCESS
        )
    }

    /** 每日状态推送：独立 ID，保留在通知栏直到用户查看 */
    fun status(ctx: Context, title: String, text: String) {
        alert(ctx, title, text, id = ID_STATUS)
    }
}

/**
 * 同一条打卡记录只推一次「打卡成功」。
 * 只在明确判定成功的路径调用；「已提交/待确认」不推，避免误报。
 */
object SuccessNotifier {
    fun onSuccess(ctx: Context, slot: String) {
        if (!Prefs.markSuccessNotified(ctx, slot)) {
            Prefs.log(ctx, "今日$slot 成功通知已推送过，跳过")
            return
        }
        Notifier.success(ctx, slot)
        Prefs.log(ctx, "推送打卡成功通知：$slot")
    }
}
