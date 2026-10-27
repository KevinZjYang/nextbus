package com.yg.busisland

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yg.busisland.ui.Ds
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold
import com.yg.busisland.ui.colorOnSurface
import com.yg.busisland.ui.colorOnSurfaceVariant
import com.yg.busisland.ui.colorPrimary
import com.yg.busisland.ui.dp

/** 到站提醒：开关、提前几分钟、铃声、岛上岛提示音 */
class ReminderActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold
    private lateinit var tvTone: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(this, "到站提醒", "车快到站时弹通知", showBack = true, onBack = { finish() })
        render()
        setContentView(page.root)
    }

    private fun render() {
        page.clearBody()

        page.section("提醒")
        val card = page.card()
        ListRow.switch(card, "🔔", "启用到站提醒", null, prefs.remindEnabled) { on ->
            prefs.remindEnabled = on
            if (!on) prefs.remindEnabled = false
            render()
        }
        tvTone = ListRow.add(card, "🎵", "提示音", toneLabel()) { openChannelSettings() }

        page.section("提前时间")
        val advCard = page.card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(14))
        }
        for (m in intArrayOf(1, 2, 3, 5, 10)) {
            val on = prefs.remindMinutes == m
            row.addView(TextView(this).apply {
                text = "$m 分钟"
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                background = Ui.pill(if (on) colorPrimary() else 0x14000000, dp(16))
                setTextColor(if (on) 0xFFFFFFFF.toInt() else colorOnSurface())
                setOnClickListener {
                    prefs.remindMinutes = m
                    prefs.remindEnabled = true
                    render()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (m != 1) marginStart = dp(6) })
        }
        advCard.addView(row)
        ListRow.switch(advCard, "🏝", "岛上岛时提示音", "岛第一次出现时响一声", prefs.islandSound) {
            prefs.islandSound = it
        }
        page.hint("提前时间按车牌到站预测分钟数算；提示音跟随系统通知管理。")

        if (BuildConfig.DEBUG) {
            page.button("发一条测试提醒", primary = false) { sendTestRemind() }
        }
    }

    private fun toneLabel(): String {
        if (!prefs.remindSound) return "已关"
        if (!Notify.channelHasSound(this, true)) return "无铃声（App 兜底响一声）"
        return Sfx.titleOf(this, Notify.currentSound(this, true))
    }

    /** 跳到系统的「通知管理 → 到站提醒」渠道页，在那里选铃声 */
    private fun openChannelSettings() {
        val ch = Notify.ensureArrival(this, prefs.remindSound)
        val direct = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ch)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { startActivity(direct); true }.getOrDefault(false)
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun sendTestRemind() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val ch = Notify.ensureArrival(this, prefs.remindSound)
        val pi = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("203路 3 分钟后到站")
            .setContentText("往 北郊客运站 · 泰山街加气站")
            .setStyle(android.app.Notification.BigTextStyle().bigText("往 北郊客运站 · 泰山街加气站"))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .build()
        runCatching { nm.notify(2001, n) }
        if (prefs.remindSound) Sfx.playNotification(this, Notify.currentSound(this, prefs.remindSound))
        toast("已发测试提醒")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
