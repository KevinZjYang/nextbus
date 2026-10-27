package com.yg.busisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "com.yg.busisland.action.TICK"
        const val ACTION_REFRESH = "com.yg.busisland.action.REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        when (intent.action) {
            ACTION_REFRESH -> {
                if (prefs.enabled) {
                    context.startService(
                        Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_REFRESH)
                    )
                }
            }
            else -> {
                if (prefs.enabled) MonitorService.start(context)
            }
        }
        if (prefs.enabled) Scheduler.armNext(context)
    }
}
