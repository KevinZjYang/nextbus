package com.yg.busisland

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** adb shell am broadcast -a com.yg.busisland.TEST_ISLAND -n com.yg.busisland/.TestReceiver */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Log.i("BusIslandTest", "enabled=${nm.areNotificationsEnabled()} channel=${nm.getNotificationChannel(HyperIsland.CHANNEL_ID)?.importance}")
        runCatching { HyperIsland.showMedia(context, "1 路", "3 分钟", "往 新首钢 · 下一班 8 分钟") }
            .onFailure { Log.e("BusIslandTest", "show failed", it) }
    }
}
