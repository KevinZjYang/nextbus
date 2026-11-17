package com.yg.busisland

import android.app.NotificationManager
import android.content.Context

/** 升级后清掉旧版本留下的通勤/岛通知与渠道。 */
object LegacyCleanup {

    private val OLD_CHANNELS = listOf(
        "bus_island",
        "bus_monitor",
        "bus_arrival_v6",
        "bus_arrival_v6_silent"
    )

    fun run(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        OLD_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        runCatching { nm.cancel(1001) }
        runCatching { nm.cancel(2001) }
    }
}
