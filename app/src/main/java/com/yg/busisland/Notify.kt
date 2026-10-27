package com.yg.busisland

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 到站提醒的两个渠道：声音由「系统通知管理」决定 */
object Notify {

    const val CH_SOUND = "bus_arrival_v6"
    const val CH_SILENT = "bus_arrival_v6_silent"

    /** 早期版本留下的一堆渠道，启动时清掉，免得通知管理里出现好几个“到站提醒” */
    private val OBSOLETE = listOf(
        "bus_arrival", "bus_arrival_sound", "bus_arrival_silent",
        "bus_arrival_v3_sound", "bus_arrival_v3_silent", "bus_arrival_v5",
        "bus_island_sound"
    )

    /** 清掉历史遗留的重复到站提醒 */
    fun clearReminders(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (id in intArrayOf(2001)) runCatching { nm.cancel(id) }
    }

    fun cleanupOldChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (id in OBSOLETE) runCatching { nm.deleteNotificationChannel(id) }
    }

    fun channelId(sound: Boolean) = if (sound) CH_SOUND else CH_SILENT

    fun ensureArrival(ctx: Context, sound: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = channelId(sound)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(
                id,
                if (sound) "到站提醒" else "到站提醒（静音）",
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 250, 200, 250)
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            if (sound) {
                ch.setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else {
                ch.setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
        return id
    }

    /** 渠道里是否还有铃声（用户可能在系统里设成“无铃声”） */
    fun channelHasSound(ctx: Context, sound: Boolean): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.getNotificationChannel(channelId(sound))?.sound != null
    }

    /** 当前渠道实际用的铃声（系统里可能被用户改过） */
    fun currentSound(ctx: Context, sound: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = nm.getNotificationChannel(channelId(sound))
        val uri = ch?.sound ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        return uri?.toString().orEmpty()
    }
}
