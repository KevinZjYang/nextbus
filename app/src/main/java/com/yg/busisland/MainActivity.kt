package com.yg.busisland

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.util.Calendar

class MainActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var enableBox: CheckBox
    private lateinit var root: LinearLayout

    private val weekNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.migrateLegacyPlaces()
        Notify.cleanupOldChannels(this)
        Notify.clearReminders(this)
        HyperIsland.ensureChannel(this)
        buildUi()
        requestRuntimePermissions()
        render()
        backfillEndpoints()
        if (BuildConfig.DEBUG) {
            if (intent?.hasExtra("debug") == true) prefs.debugMode = intent.getBooleanExtra("debug", false)
            if (intent?.getBooleanExtra("selftest", false) == true ||
                intent?.getBooleanExtra("islandtest", false) == true
            ) {
                startActivity(Intent(this, DebugActivity::class.java).apply {
                    putExtra("selftest", intent.getBooleanExtra("selftest", false))
                    putExtra("islandtest", intent.getBooleanExtra("islandtest", false))
                })
            }
        }
        if (intent?.getBooleanExtra("adhoc", false) == true) refreshNow()
        if (intent?.getBooleanExtra("startmonitor", false) == true) {
            prefs.enabled = true
            MonitorService.start(this)
        }
        if (prefs.cityId.isBlank()) ensureCity()

    }

    /** 点「灵动岛」打开系统的应用通知设置 */
    private fun openIslandSettings() {
        val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, HyperIsland.CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 老订阅没有起终点，启动时补一次 */
    private fun backfillEndpoints() {
        val missing = prefs.lines.filter { it.startSn.isBlank() || it.endSn.isBlank() }
        if (missing.isEmpty()) return
        Thread {
            val list = prefs.lines.toMutableList()
            var changed = false
            for (m in missing) {
                val arr = runCatching {
                    BusApiClient.routeStations(m.cityId, m.lineId, m.lineName, prefs.udid)
                }.getOrNull() ?: continue
                if (arr.length() < 2) continue
                val first = arr.optJSONObject(0)?.optString("sn").orEmpty()
                val last = arr.optJSONObject(arr.length() - 1)?.optString("sn").orEmpty()
                val idx = list.indexOfFirst {
                    it.lineId == m.lineId && it.stationId == m.stationId && it.place == m.place
                }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(startSn = first, endSn = last)
                    changed = true
                }
            }
            if (changed) runOnUiThread {
                prefs.lines = list
                render()
            }
        }.start()
    }

    /** 没设城市时用第一个地点自动识别一次 */
    private fun ensureCity() {
        val p = prefs.places.firstOrNull { it.valid } ?: return
        Thread {
            val c = runCatching { BusApiClient.localCity(p.lat, p.lng, prefs.udid) }.getOrNull() ?: return@Thread
            prefs.cityId = c.id
            prefs.cityName = c.name
            prefs.cityLat = c.lat
            prefs.cityLng = c.lng
        }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("islandtest", false) == true) {
            startActivity(Intent(this, DebugActivity::class.java).putExtra("islandtest", true))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ------------------------------------------------------------ UI

    // ------------------------------------------------------------ UI

    private lateinit var tvChipMonitor: TextView
    private lateinit var tvChipIsland: TextView
    private lateinit var swMonitor: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvIsland: TextView
    private lateinit var tvMonitorSet: TextView
    private lateinit var tvSettings: TextView
    private lateinit var tvPlaces: TextView
    private lateinit var tvLines: TextView
    private lateinit var tvRemind: TextView

    private fun attrColor(attr: Int, fallback: Int = Color.BLACK): Int =
        com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF2F2F7.toInt()))
            setPadding(0, Ui.statusBarHeight(this@MainActivity), 0, 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(2))
        }
        header.addView(TextView(this).apply {
            text = "下一班"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
        })
        header.addView(TextView(this).apply {
            text = "公交到站 · 上岛提醒"
            textSize = 13f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(3), 0, 0)
        })
        page.addView(header)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(2))
        }
        tvChipMonitor = chip()
        tvChipIsland = chip()
        chips.addView(tvChipMonitor, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        chips.addView(tvChipIsland)
        page.addView(chips)

        val scroll = androidx.core.widget.NestedScrollView(this)
        val navInset = window?.decorView?.let {
            androidx.core.view.ViewCompat.getRootWindowInsets(it)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        } ?: 0
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(12) + maxOf(navInset, dp(24)))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- 监控
        content.addView(section("通勤监控"))
        val monitorCard = newCard(content)
        swMonitor = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "启用通勤监控"
            textSize = 16f
            isChecked = prefs.enabled
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnCheckedChangeListener { _, checked ->
                prefs.enabled = checked
                if (checked) {
                    MonitorService.start(this@MainActivity)
                    Scheduler.armNext(this@MainActivity)
                } else {
                    MonitorService.stop(this@MainActivity)
                    Scheduler.cancel(this@MainActivity)
                    HyperIsland.cancel(this@MainActivity)
                }
                render()
            }
        }
        monitorCard.addView(swMonitor)
        tvMonitorSet = row(monitorCard, "⏱", "通勤监控设置") {
            startActivity(Intent(this, MonitorActivity::class.java))
        }
        tvIsland = row(monitorCard, "🏝", "灵动岛") { openIslandSettings() }
        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(dp(16), dp(2), dp(16), dp(6))
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        monitorCard.addView(statusView)

        // 主操作跟着概览卡走，不再做贴底常驻条
        content.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "立即查询一次"
            isAllCaps = false
            textSize = 15f
            setOnClickListener { refreshNow() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })

        // ---- 地点
        content.addView(section("地点"))
        val placeCard = newCard(content)
        tvPlaces = row(placeCard, "📍", "常去地点") {
            startActivity(Intent(this, PlacesActivity::class.java))
        }
        val tvNearby = row(placeCard, "🧭", "附近公交") {
            startActivity(
                Intent(this, NearbyLinesActivity::class.java)
                    .putExtra(NearbyLinesActivity.EXTRA_USE_CURRENT, true)
            )
        }
        tvNearby.text = "当前位置"

        // ---- 提醒
        content.addView(section("提醒规则"))
        val ruleCard = newCard(content)
        tvLines = row(ruleCard, "🚌", "订阅线路") {
            startActivity(Intent(this, LinesActivity::class.java))
        }
        tvRemind = row(ruleCard, "🔔", "到站提醒") {
            startActivity(Intent(this, ReminderActivity::class.java))
        }


        // ---- 设置
        content.addView(section("设置"))
        val setCard = newCard(content)
        tvSettings = row(setCard, "⚙️", "围栏、后台与关于") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setContentView(page)
    }

    private fun chip(): TextView = TextView(this).apply {
        textSize = 12f
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(30).toFloat()
            setColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
            setStroke(dp(1), 0x1A000000)
        }
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 12.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(dp(8), dp(14), 0, dp(6))
    }

    /** 建一张卡片并塞进父容器，返回卡片内部的纵向容器 */
    private fun newCard(parent: LinearLayout): LinearLayout {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = 0x14000000
            setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(body)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2))
        return body
    }

    /** 一行：emoji + 标题 + 右侧值 + ›，返回右侧值控件 */
    private fun row(parent: LinearLayout, emoji: String, title: String, onClick: (() -> Unit)?): TextView {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            if (onClick != null) {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        line.addView(TextView(this).apply { text = emoji; textSize = 17f })
        line.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), 0, dp(8), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val value = TextView(this).apply {
            textSize = 13f
            maxLines = 1
            maxWidth = (resources.displayMetrics.widthPixels * 0.52f).toInt()
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        line.addView(value)
        if (onClick != null) {
            line.addView(TextView(this).apply {
                text = "›"
                textSize = 19f
                setPadding(dp(6), 0, 0, 0)
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        line.contentDescription = when {
            value.text.isNullOrBlank() -> title
            else -> "$title ${value.text}"
        }
        parent.addView(line)
        return value
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun islandLabel(): String {
        val sup = HyperIsland.isSupported()
        val proto = HyperIsland.protocolVersion(this)
        val perm = HyperIsland.hasFocusPermission(this)
        return when {
            !sup -> "系统不支持"
            proto < 3 -> "焦点通知 OS$proto"
            perm -> "已开启"
            else -> "未授权"
        }
    }

    private fun scheduleSummary(): String {
        val s = prefs.schedule
        fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        // 概览行只放时段，工作日进设置页看，避免挤压标题
        return "${hm(s.morningStart)}–${hm(s.morningEnd)} · ${hm(s.eveningStart)}–${hm(s.eveningEnd)}"
    }

    private fun render() {
        swMonitor.isChecked = prefs.enabled
        tvChipMonitor.text = if (prefs.enabled) "● 监控中" else "○ 已关闭"
        tvChipMonitor.setTextColor(if (prefs.enabled) 0xFF1F7A3D.toInt() else Color.GRAY)
        tvChipIsland.text = "🏝 ${islandLabel()}"
        tvIsland.text = islandLabel()
        tvMonitorSet.text = scheduleSummary()
        tvLines.text = "${prefs.lines.size} 条"
        tvPlaces.text = "${prefs.places.size} 个"
        tvRemind.text = if (prefs.remindEnabled)
            "剩余 ${prefs.remindMinutes} 分钟 · ${if (prefs.remindSound) "有声" else "静音"}"
        else "已关闭"
        val adhoc = System.currentTimeMillis() < prefs.adhocUntil
        if (adhoc) {
            tvChipMonitor.text = "● 临时查询"
            tvChipMonitor.setTextColor(0xFF9A5B00.toInt())
        }
        statusView.text = prefs.lastStatus.ifBlank { "最近没有查询记录" }

    }

    // ------------------------------------------------------------ 城市

    // ------------------------------------------------------------ 订阅

    // ------------------------------------------------------------ 参数

    private fun editTuning() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val radius = EditText(this).apply {
            hint = "围栏半径（米）"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.radius.toString())
        }
        val interval = EditText(this).apply {
            hint = "刷新间隔（秒）"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.intervalSec.toString())
        }
        box.addView(radius)
        box.addView(interval)
        AlertDialog.Builder(this)
            .setTitle("参数")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.radius = radius.text.toString().toIntOrNull()?.coerceIn(100, 5000) ?: prefs.radius
                prefs.intervalSec = interval.text.toString().toIntOrNull()?.coerceIn(20, 300) ?: prefs.intervalSec
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshNow() {
        if (prefs.lines.isEmpty()) {
            toast("先订阅线路")
            startActivity(Intent(this, LinesActivity::class.java))
            return
        }
        prefs.adhocUntil = System.currentTimeMillis() + 10 * 60_000L
        MonitorService.start(this)
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_REFRESH))
        render()
        toast("查询中…结果会显示 10 分钟")
    }

    // ------------------------------------------------------------ 保活

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty() && !prefs.askedPermissions) {
            prefs.askedPermissions = true
            requestPermissions(need.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            toast("未授予定位/通知权限，功能会受限")
        }
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

}
