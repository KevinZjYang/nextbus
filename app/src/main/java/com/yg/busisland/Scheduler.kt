package com.yg.busisland

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** 用精确闹钟在通勤时段开始/结束时唤醒服务（服务本身常驻，闹钟作为进程被杀后的兜底）。 */
object Scheduler {

    private fun pending(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun armNext(ctx: Context) {
        val prefs = Prefs(ctx)
        val at = nextBoundary(prefs.schedule, System.currentTimeMillis()) ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, AlarmReceiver.ACTION_TICK)
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, AlarmReceiver.ACTION_TICK))
    }

    /** 下一个时段起点（含开始与结束，用于唤醒后重新评估） */
    fun nextBoundary(s: Schedule, now: Long): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var best: Long? = null
        for (d in 0..8) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            for (minute in intArrayOf(s.morningStart, s.morningEnd, s.eveningStart, s.eveningEnd)) {
                val t = Calendar.getInstance().apply {
                    timeInMillis = c.timeInMillis
                    set(Calendar.HOUR_OF_DAY, minute / 60)
                    set(Calendar.MINUTE, minute % 60)
                }.timeInMillis
                if (t > now + 60_000 && (best == null || t < best!!)) best = t
            }
        }
        return best
    }
}
