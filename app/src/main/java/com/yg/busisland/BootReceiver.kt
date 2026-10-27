package com.yg.busisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.enabled) return
        runCatching { MonitorService.start(context) }
        Scheduler.armNext(context)
    }
}
