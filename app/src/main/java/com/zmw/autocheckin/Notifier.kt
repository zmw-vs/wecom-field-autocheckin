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

object Notifier {

    private const val CHANNEL = "checkin_alert"
    private const val ID = 2001

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

    fun alert(ctx: Context, title: String, text: String, openWework: Boolean = false) {
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
            NotificationManagerCompat.from(ctx).notify(ID, n)
        } catch (_: SecurityException) {
            Prefs.log(ctx, "通知权限未授予,提醒未能展示")
        }
    }
}
