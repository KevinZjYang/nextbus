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
import org.json.JSONObject
import java.util.Calendar

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var enableBox: CheckBox
    private lateinit var root: LinearLayout

    private val weekNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = !isNightMode()
        prefs = Prefs(this)
        prefs.migrateLegacyPlaces()
        Notify.cleanupOldChannels(this)
        Notify.clearReminders(this)
        HyperIsland.ensureChannel(this)
        buildUi()
        requestRuntimePermissions()
        render()
        backfillEndpoints()
        // 调试验证用：先退出界面，再延迟发一条提醒，避免 MIUI 对前台应用吞掉提示音
        if (intent?.getBooleanExtra("testremind", false) == true) {
            finish()
            android.os.Handler(mainLooper).postDelayed({ sendTestRemind() }, 1500)
            return
        }
        if (intent?.hasExtra("debug") == true) prefs.debugMode = intent.getBooleanExtra("debug", false)
        if (intent?.getBooleanExtra("adhoc", false) == true) refreshNow()
        if (intent?.getBooleanExtra("startmonitor", false) == true) {
            prefs.enabled = true
            MonitorService.start(this)
        }
        if (prefs.cityId.isBlank()) {
            val p = prefs.places.firstOrNull { it.valid }
            if (p != null) resolveCity(p.lat, p.lng)
        }
        if (intent?.getBooleanExtra("selftest", false) == true) selfTest()
        if (intent?.getBooleanExtra("islandtest", false) == true) sendTestIsland()
    }

    private fun islandCaps(): String =
        "supported=${HyperIsland.isSupported()} protocol=${HyperIsland.protocolVersion(this)} " +
            "focus=${HyperIsland.hasFocusPermission(this)}"

    /** adb shell am start -n com.yg.busisland/.MainActivity --ez selftest true */
    private fun selfTest() {
        statusView.text = "自检中…"
        Thread {
            val log = StringBuilder()
            runCatching {
                val lines = BusApiClient.search("027", "1", prefs.udid)
                log.append("search=").append(lines.size).append('\n')
                val first = lines.first()
                val lineId = first.optString("lineId")
                val name = first.optString("name")
                log.append("line=").append(lineId).append(' ').append(name).append('\n')
                val st = BusApiClient.routeStations("027", lineId, name, prefs.udid)
                log.append("stations=").append(st.length()).append('\n')
                if (st.length() > 1) {
                    val s = st.getJSONObject(1)
                    val sub = LineSub("027", lineId, name, name, first.optInt("direction", 0),
                        s.optInt("order"), s.optString("sn"), s.optString("sId"), "home")
                    val r = BusApiClient.realtime(sub, prefs.udid)
                    log.append("realtime ok=").append(r.ok).append(" desc=").append(r.lineDesc)
                        .append(" buses=").append(r.buses.size)
                }
            }.onFailure { log.append("ERR ").append(it.message) }
            android.util.Log.i("BusIslandSelfTest", log.toString())
            runOnUiThread {
                statusView.text = log.toString()
                Toast.makeText(this, "自检完成，见 logcat", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("islandtest", false) == true) {
                sendTestIsland()
        }
        if (intent?.getBooleanExtra("selftest", false) == true) selfTest()
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
    private lateinit var tvCity: TextView
    private lateinit var tvIsland: TextView
    private lateinit var placesBox: LinearLayout
    private lateinit var tvSchedule: TextView
    private lateinit var tvLines: TextView
    private lateinit var tvAdvanced: TextView
    private lateinit var tvDebug: TextView
    private lateinit var tvRemind: TextView
    private lateinit var linesBox: LinearLayout
    private lateinit var tvKeepAuto: TextView
    private lateinit var tvKeepBattery: TextView
    private lateinit var tvKeepPower: TextView

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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(36))
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
        tvCity = row(monitorCard, "🏙", "城市") { pickCity() }
        tvIsland = row(monitorCard, "🏝", "灵动岛") { islandInfo() }
        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(dp(16), dp(2), dp(16), dp(14))
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        monitorCard.addView(statusView)

        // ---- 地点
        content.addView(section("地点"))
        val placeCard = newCard(content)
        placesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        placeCard.addView(placesBox)

        // ---- 提醒
        content.addView(section("提醒规则"))
        val ruleCard = newCard(content)
        tvSchedule = row(ruleCard, "⏰", "通勤时段") { editSchedule() }
        tvLines = row(ruleCard, "🚌", "订阅线路") { manageLines() }
        tvRemind = row(ruleCard, "🔔", "到站提醒") { editRemind() }
        linesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ruleCard.addView(linesBox)

        // ---- 高级
        content.addView(section("高级"))
        val advCard = newCard(content)
        tvAdvanced = row(advCard, "⚙️", "围栏与刷新") { editTuning() }
        tvDebug = row(advCard, "🧪", "调试模式") {
            prefs.debugMode = !prefs.debugMode
            render()
        }

        // ---- 后台运行
        content.addView(section("后台运行"))
        val keepCard = newCard(content)
        tvKeepAuto = row(keepCard, "🚀", "自启动") { openAutoStart() }
        tvKeepBattery = row(keepCard, "🔋", "电池优化") { requestIgnoreBattery() }
        tvKeepPower = row(keepCard, "⚡", "省电策略") { openPowerSaver() }
        keepCard.addView(TextView(this).apply {
            text = "三项都设好，后台才不会被系统清掉"
            textSize = 12f
            setPadding(dp(16), dp(2), dp(16), dp(14))
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        // ---- 操作
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                text = "立即查询一次"
                isAllCaps = false
                textSize = 15f
                setOnClickListener { refreshNow() }
            },
            LinearLayout.LayoutParams(-1, dp(50))
        )
        actions.addView(
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "发送测试岛"
                isAllCaps = false
                textSize = 15f
                setOnClickListener { sendTestIsland() }
            },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) }
        )
        content.addView(actions)

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
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val value = TextView(this).apply {
            textSize = 13f
            maxLines = 1
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
        val days = buildString {
            val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
            val on = (0..6).filter { s.weekdays and (1 shl it) != 0 }
            if (on.isEmpty()) append("未选")
            else if (on == listOf(0, 1, 2, 3, 4)) append("工作日")
            else if (on.size == 7) append("每天")
            else append(on.joinToString("") { names[it] })
        }
        return "${hm(s.morningStart)}–${hm(s.morningEnd)} / ${hm(s.eveningStart)}–${hm(s.eveningEnd)} · $days"
    }

    private fun render() {
        swMonitor.isChecked = prefs.enabled
        tvChipMonitor.text = if (prefs.enabled) "● 监控中" else "○ 已关闭"
        tvChipMonitor.setTextColor(if (prefs.enabled) 0xFF1F7A3D.toInt() else Color.GRAY)
        tvChipIsland.text = "🏝 ${islandLabel()}"
        tvCity.text = if (prefs.cityId.isBlank()) "未设置" else "${prefs.cityName} ${prefs.cityId}"
        tvIsland.text = islandLabel()
        renderPlaces()
        tvSchedule.text = scheduleSummary()
        tvLines.text = "${prefs.lines.size} 条"
        tvAdvanced.text = "${prefs.radius}m · ${prefs.intervalSec}s"
        tvDebug.text = if (prefs.debugMode) "开（忽略时段）" else "关"
        tvRemind.text = if (prefs.remindEnabled)
            "剩余 ${prefs.remindMinutes} 分钟 · ${if (prefs.remindSound) "有声" else "静音"}"
        else "已关闭"
        val adhoc = System.currentTimeMillis() < prefs.adhocUntil
        if (adhoc) {
            tvChipMonitor.text = "● 临时查询"
            tvChipMonitor.setTextColor(0xFF9A5B00.toInt())
        }
        tvKeepBattery.text = if (batteryIgnored()) "已忽略" else "去设置"
        statusView.text = prefs.lastStatus.ifBlank { "最近没有查询记录" }

        linesBox.removeAllViews()
        for (l in prefs.lines) {
            linesBox.addView(TextView(this).apply {
                text = buildString {
                    append("· ").append(l.title).append("  ").append(l.stationName)
                    if (l.routeText.isNotBlank()) append("  ").append(l.routeText)
                    append("  ").append(prefs.placeById(l.place)?.name ?: "未绑定")
                }
                textSize = 12.5f
                setPadding(dp(46), 0, dp(16), dp(8))
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
    }

    // ------------------------------------------------------------ 城市

    private fun cityLabel(): String =
        if (prefs.cityId.isBlank()) "未设置（点我选择）" else "${prefs.cityName}（${prefs.cityId}）"

    private fun pickCity() {
        val items = arrayOf("自动检测（用家/公司坐标）", "搜索城市名", "用当前位置定位城市")
        AlertDialog.Builder(this)
            .setTitle("当前城市：${cityLabel()}")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> autoCityFromPlaces()
                    1 -> searchCity()
                    else -> autoCityFromLocation()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun autoCityFromPlaces() {
        val p = prefs.places.firstOrNull { it.valid }
        if (p == null) {
            toast("先设置家或公司，或改用「搜索城市名」")
            return
        }
        resolveCity(p.lat, p.lng)
    }

    private fun autoCityFromLocation() {
        val mgr = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = runCatching {
            mgr.getLastKnownLocation("fused")
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        if (loc == null) {
            toast("暂无可用的定位缓存，请用「搜索城市名」")
            return
        }
        resolveCity(loc.latitude, loc.longitude)
    }

    private fun resolveCity(lat: Double, lng: Double) {
        toast("识别城市中…")
        Thread {
            val c = runCatching { BusApiClient.localCity(lat, lng, prefs.udid) }.getOrNull()
            runOnUiThread {
                if (c == null) {
                    toast("城市识别失败，请手动搜索")
                    return@runOnUiThread
                }
                prefs.cityId = c.id
                prefs.cityName = c.name
                prefs.cityLat = c.lat
                prefs.cityLng = c.lng
                render()
                toast("城市：${c.name}（${c.id}）")
            }
        }.start()
    }

    private var cityCache: List<CityInfo> = emptyList()

    private fun searchCity() {
        val box = EditText(this).apply { hint = "城市名，如 乌鲁木齐 / 北京" }
        AlertDialog.Builder(this)
            .setTitle("搜索城市")
            .setView(box)
            .setPositiveButton("搜索") { _, _ ->
                val key = box.text.toString().trim()
                if (key.isBlank()) return@setPositiveButton
                toast("加载城市列表…")
                Thread {
                    if (cityCache.isEmpty()) {
                        cityCache = runCatching { BusApiClient.cities(prefs.udid) }.getOrDefault(emptyList())
                    }
                    val hits = cityCache.filter {
                        it.name.contains(key) || it.pinyin.contains(key, ignoreCase = true)
                    }.take(30)
                    runOnUiThread {
                        if (hits.isEmpty()) {
                            toast("没有匹配的城市")
                            return@runOnUiThread
                        }
                        AlertDialog.Builder(this)
                            .setTitle("选择城市")
                            .setItems(hits.map { "${it.name}（${it.id}）" }.toTypedArray()) { _, i ->
                                val c = hits[i]
                                prefs.cityId = c.id
                                prefs.cityName = c.name
                                prefs.cityLat = c.lat
                                prefs.cityLng = c.lng
                                render()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 地点

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

    private fun renderPlaces() {
        placesBox.removeAllViews()
        val places = prefs.places
        if (places.isEmpty()) {
            placesBox.addView(TextView(this).apply {
                text = "还没有地点，点下面「添加地点」"
                textSize = 13f
                setPadding(dp(16), dp(10), dp(16), dp(4))
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        for (p in places) {
            val emoji = when (p.id) {
                "home" -> "🏠"
                "company" -> "🏢"
                else -> "📍"
            }
            val value = row(placesBox, emoji, p.name.ifBlank { "地点" }) { editPlaceMenu(p) }
            val parts = mutableListOf<String>()
            parts.add(if (p.valid) "已设点" else "未设坐标")
            val cnt = prefs.lines.count { it.place == p.id }
            if (cnt > 0) parts.add("$cnt 条")
            value.text = parts.joinToString(" · ")
        }
        row(placesBox, "＋", "添加地点") { addPlace() }
        row(placesBox, "🧭", "看看附近有什么公交") {
            startActivity(
                Intent(this, NearbyLinesActivity::class.java)
                    .putExtra(NearbyLinesActivity.EXTRA_USE_CURRENT, true)
            )
        }
    }

    private fun editPlace(id: String) {
        startActivity(
            Intent(this, PlacePickerActivity::class.java)
                .putExtra(PlacePickerActivity.EXTRA_PLACE_ID, id)
        )
    }

    private fun editPlaceMenu(p: Place) {
        val items = arrayOf("重新选点", "重命名", "删除地点")
        AlertDialog.Builder(this)
            .setTitle(p.name.ifBlank { "地点" })
            .setItems(items) { _, i ->
                when (i) {
                    0 -> editPlace(p.id)
                    1 -> renamePlace(p)
                    else -> {
                        prefs.removePlace(p.id)
                        render()
                        toast("已删除")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renamePlace(p: Place) {
        val box = EditText(this).apply { setText(p.name) }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.upsertPlace(p.copy(name = box.text.toString().trim().ifBlank { p.name }))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addPlace() {
        val box = EditText(this).apply { hint = "地点名，如 家 / 公司 / 学校" }
        AlertDialog.Builder(this)
            .setTitle("添加地点")
            .setView(box)
            .setPositiveButton("下一步") { _, _ ->
                val name = box.text.toString().trim().ifBlank { "地点" }
                val id = prefs.newPlaceId()
                prefs.upsertPlace(Place(id = id, name = name))
                editPlace(id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 时段

    private fun editSchedule() {
        val s = prefs.schedule
        val items = arrayOf(
            "早高峰开始 ${fmt(s.morningStart)}",
            "早高峰结束 ${fmt(s.morningEnd)}",
            "晚高峰开始 ${fmt(s.eveningStart)}",
            "晚高峰结束 ${fmt(s.eveningEnd)}",
            "工作日：${weekSummary(s.weekdays)}"
        )
        AlertDialog.Builder(this)
            .setTitle("通勤时段")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> pickTime(s.morningStart) { prefs.schedule = s.copy(morningStart = it); editSchedule() }
                    1 -> pickTime(s.morningEnd) { prefs.schedule = s.copy(morningEnd = it); editSchedule() }
                    2 -> pickTime(s.eveningStart) { prefs.schedule = s.copy(eveningStart = it); editSchedule() }
                    3 -> pickTime(s.eveningEnd) { prefs.schedule = s.copy(eveningEnd = it); editSchedule() }
                    else -> pickWeekdays(s)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun pickTime(cur: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, h, m -> onPick(h * 60 + m) },
            cur / 60, cur % 60, true
        ).show()
    }

    private fun pickWeekdays(s: Schedule) {
        val checked = BooleanArray(7) { s.weekdays and (1 shl it) != 0 }
        AlertDialog.Builder(this)
            .setTitle("工作日")
            .setMultiChoiceItems(weekNames, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                var mask = 0
                for (i in 0 until 7) if (checked[i]) mask = mask or (1 shl i)
                prefs.schedule = s.copy(weekdays = mask)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun weekSummary(mask: Int): String {
        val on = (0 until 7).filter { mask and (1 shl it) != 0 }.map { weekNames[it] }
        return if (on.isEmpty()) "未选择" else on.joinToString("、")
    }

    private fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    // ------------------------------------------------------------ 订阅

    private fun manageLines() {
        val list = prefs.lines
        val items = if (list.isEmpty()) arrayOf("暂无订阅，点此新增") else
            list.map { "${it.title} · ${it.stationName} · ${prefs.placeById(it.place)?.name ?: "未绑定"}" }
                .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("订阅线路")
            .setItems(items) { _, i ->
                if (list.isEmpty()) addLine()
                else AlertDialog.Builder(this)
                    .setTitle(items[i])
                    .setPositiveButton("删除") { _, _ ->
                        prefs.lines = list.filterIndexed { idx, _ -> idx != i }.toMutableList()
                        render()
                    }
                    .setNegativeButton("返回", null)
                    .show()
            }
            .setPositiveButton("新增") { _, _ -> addLine() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun addLine() {
        val places = prefs.places.filter { it.valid }
        if (places.isEmpty()) {
            toast("请先添加并选点一个地点")
            addPlace()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("线路绑定到")
            .setItems(places.map { it.name.ifBlank { "地点" } }.toTypedArray()) { _, i ->
                val place = places[i]
                AlertDialog.Builder(this)
                    .setTitle("给「${place.name.ifBlank { "地点" }}」添加线路")
                    .setItems(arrayOf("看附近站点（推荐）", "按线路号搜索")) { _, j ->
                        if (j == 0) {
                            startActivity(
                                Intent(this, NearbyLinesActivity::class.java)
                                    .putExtra(NearbyLinesActivity.EXTRA_PLACE_ID, place.id)
                            )
                        } else {
                            searchLine(place.id)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun searchLine(place: String) {
        val cid = prefs.cityId
        if (cid.isBlank()) {
            toast("先确定城市")
            pickCity()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val key = EditText(this).apply { hint = "线路关键词，如 1 或 地铁" }
        box.addView(key)

        AlertDialog.Builder(this)
            .setTitle("搜索线路（${prefs.cityName} ${cid}）")
            .setView(box)
            .setPositiveButton("搜索") { _, _ ->
                val k = key.text.toString().trim()
                if (k.isEmpty()) {
                    toast("请输入关键词")
                    return@setPositiveButton
                }
                toast("搜索中…")
                Thread {
                    val result = runCatching { BusApiClient.search(cid, k, prefs.udid) }
                    runOnUiThread {
                        result.onSuccess { pickLine(cid, place, it) }
                            .onFailure { toast("搜索失败：${it.message}") }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickLine(cityId: String, place: String, lines: List<JSONObject>) {
        if (lines.isEmpty()) {
            toast("没有结果")
            return
        }
        val labels = lines.map { o ->
            val start = o.optString("startSn")
            val end = o.optString("endSn")
            "${o.optString("name")}  ${if (start.isBlank()) "" else "$start → $end"}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择线路")
            .setItems(labels) { _, i ->
                val o = lines[i]
                loadStations(
                    cityId,
                    o.optString("lineId"),
                    o.optString("name"),
                    o.optString("lineNo", o.optString("name")),
                    o.optInt("direction", 0),
                    place
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadStations(cityId: String, lineId: String, lineName: String, lineNo: String, dir: Int, place: String) {
        toast("加载站点…")
        Thread {
            val r = runCatching { BusApiClient.routeStations(cityId, lineId, lineName, prefs.udid) }
            runOnUiThread {
                r.onFailure { toast("站点加载失败：${it.message}") }
                r.onSuccess { arr ->
                    if (arr.length() == 0) {
                        toast("无站点数据")
                        return@onSuccess
                    }
                    val firstSn = arr.optJSONObject(0)?.optString("sn").orEmpty()
                    val lastSn = arr.optJSONObject(arr.length() - 1)?.optString("sn").orEmpty()
                    val names = (0 until arr.length()).map { idx ->
                        val s = arr.getJSONObject(idx)
                        "${s.optInt("order")}. ${s.optString("sn")}"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("${lineNo.ifBlank { lineName }}  ${firstSn} ↔ ${lastSn}")
                        .setItems(names) { _, idx ->
                            val s = arr.getJSONObject(idx)
                            val sub = LineSub(
                                cityId, lineId, lineName, lineNo, dir,
                                s.optInt("order"), s.optString("sn"), s.optString("sId"), place,
                                startSn = firstSn, endSn = lastSn
                            )
                            val list = prefs.lines
                            list.add(sub)
                            prefs.lines = list
                            render()
                            toast("已订阅：${sub.title} @ ${sub.stationName}")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

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

    private fun islandInfo() {
        val style = "媒体卡片（免授权，可直接用）"
        val msg = buildString {
            append("当前样式：").append(style).append('\n')
            append("系统支持：").append(HyperIsland.isSupported()).append('\n')
            append("协议版本：").append(HyperIsland.protocolVersion(this@MainActivity)).append('\n')
            append("焦点通知权限：").append(HyperIsland.hasFocusPermission(this@MainActivity)).append("\n\n")
            append("说明：打车那种岛是小米「焦点通知」官方模板，第三方自签名 App 会被 SystemUI 的 FocusPlugin 拒绝授权（onAuthFailed）；")
            append("媒体卡片是免授权通道，形状偏音乐卡片。")
        }
        AlertDialog.Builder(this)
            .setTitle("超级岛")
            .setMessage(msg)
            .setPositiveButton("发送测试岛") { _, _ -> sendTestIsland() }
            .setNegativeButton("权限设置") { _, _ -> HyperIsland.openFocusSettings(this) }
            .show()
    }

    private fun sendTestIsland() {
        if (prefs.islandSound) Sfx.playNotification(this, Notify.currentSound(this, prefs.remindSound))
        HyperIsland.showMedia(this, "1 路", "3 分钟", "往 新首钢 · 下一班 8 分钟")
        toast("已发送（若无岛显示，请检查焦点通知权限）")
    }

    private fun showDebug() {
        val body = prefs.lastQueryBody
        val msg = buildString {
            append("UDID: ").append(prefs.udid).append("\n\n")
            append("最近 URL:\n").append(prefs.lastQueryUrl.ifBlank { "—" }).append("\n\n")
            append("响应片段:\n").append(body.take(1200).ifBlank { "—" })
        }
        AlertDialog.Builder(this)
            .setTitle("调试")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 临时查询：10 分钟内忽略时段与围栏，查一次就上岛 */
    private fun editRemind() {
        val items = arrayOf(
            "开启 / 关闭（当前：${if (prefs.remindEnabled) "开" else "关"}）",
            "提示音开关：${if (prefs.remindSound) "开" else "关"}",
            "提示音：" + toneLabel() + "（点击修改）",
            "岛上岛时提示音：${if (prefs.islandSound) "开" else "关"}",
            "提前 1 分钟", "提前 2 分钟", "提前 3 分钟", "提前 5 分钟", "提前 10 分钟",
            "试听当前提示音", "发一条测试提醒"
        )
        AlertDialog.Builder(this)
            .setTitle("到站提醒")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> prefs.remindEnabled = !prefs.remindEnabled
                    1 -> prefs.remindSound = !prefs.remindSound
                    2 -> openChannelSettings()
                    3 -> prefs.islandSound = !prefs.islandSound
                    4 -> { prefs.remindMinutes = 1; prefs.remindEnabled = true }
                    5 -> { prefs.remindMinutes = 2; prefs.remindEnabled = true }
                    6 -> { prefs.remindMinutes = 3; prefs.remindEnabled = true }
                    7 -> { prefs.remindMinutes = 5; prefs.remindEnabled = true }
                    8 -> { prefs.remindMinutes = 10; prefs.remindEnabled = true }
                    9 -> Sfx.playNotification(this, Notify.currentSound(this, prefs.remindSound))
                    else -> sendTestRemind()
                }
                render()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 当前渠道铃声名（系统里选的） */
    private fun toneLabel(): String {
        if (!prefs.remindSound) return "已关"
        if (!Notify.channelHasSound(this, true)) return "无铃声（App 兜底响一声）"
        return Sfx.titleOf(this, Notify.currentSound(this, true))
    }

    /** 直接跳到「通知管理 → 到站提醒」渠道页，系统里改铃声/声音开关 */
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
        val n = android.app.Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("203路 3 分钟后到站")
            .setContentText("往 泰山街加气站 · 示例提醒")
            .setAutoCancel(true)
            .build()
        nm.notify(2001, n)
        toast("已发测试提醒")
    }

    private fun refreshNow() {
        if (prefs.lines.isEmpty()) {
            toast("先订阅线路")
            manageLines()
            return
        }
        prefs.adhocUntil = System.currentTimeMillis() + 10 * 60_000L
        MonitorService.start(this)
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_REFRESH))
        render()
        toast("查询中…结果会显示 10 分钟")
    }

    // ------------------------------------------------------------ 保活

    private fun batteryIgnored(): Boolean = runCatching {
        (getSystemService(android.os.PowerManager::class.java)).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    private fun openAppDetail() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    private fun openAutoStart() {
        val candidates = listOf(
            android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        )
        for (c in candidates) {
            if (runCatching { startActivity(Intent().setComponent(c)); true }.getOrDefault(false)) return
        }
        toast("没找到自启动页面，去应用详情里开「自启动」")
        openAppDetail()
    }

    private fun requestIgnoreBattery() {
        if (batteryIgnored()) {
            toast("已在电池优化白名单")
            return
        }
        val ok = runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
            true
        }.getOrDefault(false)
        if (!ok) openAppDetail()
    }

    private fun openPowerSaver() {
        val ok = runCatching {
            startActivity(Intent().setComponent(
                android.content.ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            ).putExtra("package_name", packageName).putExtra("package_label", "通勤公交"))
            true
        }.getOrDefault(false)
        if (!ok) {
            toast("去应用详情 → 省电策略 → 无限制")
            openAppDetail()
        }
    }

    // ------------------------------------------------------------ 权限

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

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
