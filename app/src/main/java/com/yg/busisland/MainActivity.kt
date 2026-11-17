package com.yg.busisland

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.NearbyLineRowView
import com.yg.busisland.ui.PageScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：打开即定位，直接列附近线路；订阅行置顶。
 * 不启动服务、不申请通知权限，也不做后台轮询。
 */
class MainActivity : com.yg.busisland.ui.PageActivity() {

    companion object {
        private const val REQ_LOCATION = 100
        private const val FRESH_MS = 30_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold
    private val loader = NearbyLoader()

    private var loading = false
    private var phase = "定位中…"
    private var lastError: String? = null
    private var data: NearbyData? = null
    private var accuracy = -1f
    private var lastLoadAt = 0L
    private var requestId = 0
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.migrateLegacyPlaces()
        prefs.dedupeLinesOnce()
        LegacyCleanup.run(this)

        page = PageScaffold(
            this, "下一班", "打开即查 · 附近公交",
            largeTitle = true
        )
        setContentView(page.root)
        render()
        started = true
        maybeStart()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!started || loading) return
        val stale = data == null || SystemClock.elapsedRealtime() - lastLoadAt >= FRESH_MS
        if (stale) maybeStart()
    }

    override fun onDestroy() {
        loader.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 触发查询

    private fun maybeStart() {
        if (loading) return
        if (hasLocationPermission()) {
            load(force = true)
            return
        }
        if (!prefs.askedPermissions) {
            prefs.askedPermissions = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
            render()
            return
        }
        val place = firstValidPlace()
        if (place != null) loadFromPlace(place) else render()
    }

    private fun load(force: Boolean) {
        if (loading) return
        if (!force && data != null && SystemClock.elapsedRealtime() - lastLoadAt < FRESH_MS) return

        if (!hasLocationPermission()) {
            val place = firstValidPlace()
            if (place != null) loadFromPlace(place) else render()
            return
        }

        loading = true
        phase = "定位中…"
        lastError = null
        accuracy = -1f
        val id = ++requestId
        render()

        AmapLocator.once(this, 8_000) { loc ->
            if (id != requestId || isFinishing || isDestroyed) return@once
            if (loc != null) {
                startQuery(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    label = "当前位置",
                    accuracy = loc.accuracy,
                    autoCity = true,
                    cityId = null,
                    id = id
                )
            } else {
                val last = lastKnown()
                if (last != null) {
                    startQuery(
                        lat = last.latitude,
                        lng = last.longitude,
                        label = "系统定位",
                        accuracy = last.accuracy,
                        autoCity = true,
                        cityId = null,
                        id = id
                    )
                } else {
                    val place = firstValidPlace()
                    if (place != null) {
                        startQuery(
                            lat = place.lat,
                            lng = place.lng,
                            label = place.name.ifBlank { "地点" } + "（缓存）",
                            accuracy = -1f,
                            autoCity = place.cityId.isBlank(),
                            cityId = place.cityId.ifBlank { null },
                            id = id
                        )
                    } else {
                        loading = false
                        lastError = "定位失败，请检查系统定位或去设置选城市"
                        render()
                    }
                }
            }
        }
    }

    private fun loadFromPlace(place: Place) {
        if (loading) return
        loading = true
        phase = "查询中…"
        lastError = null
        accuracy = -1f
        val id = ++requestId
        render()
        startQuery(
            lat = place.lat,
            lng = place.lng,
            label = place.name.ifBlank { "地点" } + "（缓存）",
            accuracy = -1f,
            autoCity = place.cityId.isBlank(),
            cityId = place.cityId.ifBlank { null },
            id = id
        )
    }

    private fun startQuery(
        lat: Double,
        lng: Double,
        label: String,
        accuracy: Float,
        autoCity: Boolean,
        cityId: String?,
        id: Int
    ) {
        this.accuracy = accuracy
        phase = "查询中…"
        render()

        loader.load(
            cityId = cityId ?: prefs.cityId,
            cityName = prefs.cityName,
            autoCity = autoCity,
            lat = lat,
            lng = lng,
            label = label,
            udid = prefs.udid,
            maxStations = 8,
            maxLines = 6,
            timeoutMs = 10_000
        ) { result ->
            if (id != requestId || isFinishing || isDestroyed) return@load
            loading = false
            result.onSuccess { d ->
                data = d
                NearbySnapshot.put(d)
                lastLoadAt = SystemClock.elapsedRealtime()
                lastError = null
                d.detectedCity?.let { c ->
                    if (c.id != prefs.cityId || c.name != prefs.cityName) prefs.saveCity(c)
                }
                prefs.lastStatus = summary(d)
                render()
            }.onFailure { e ->
                lastError = e.message ?: "未知错误"
                prefs.lastStatus = "查询失败：${lastError}"
                render()
                toast("查询失败：${lastError}")
            }
        }
    }

    private fun summary(d: NearbyData): String {
        val rows = NearbySelector.build(d.stations, d.linesByStation, prefs.lines)
        return "附近 ${d.stations.size} 站 · ${rows.size} 条线路"
    }

    // ------------------------------------------------------------ 位置

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun firstValidPlace(): Place? = prefs.places.firstOrNull { it.valid }

    private fun lastKnown(): Location? {
        val mgr = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).mapNotNull { provider ->
            runCatching { mgr.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (hasLocationPermission()) {
            load(force = true)
        } else {
            loading = false
            toast("未授予定位权限，可用常去地点兜底")
            maybeStart()
        }
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        page.clearBody()
        renderStatus()

        val d = data
        val rows = if (d != null) {
            NearbySelector.build(d.stations, d.linesByStation, prefs.lines)
        } else {
            emptyList()
        }
        val pinned = rows.filter { it.subscription != null }
        val nearby = rows.filter { it.subscription == null }

        if (pinned.isNotEmpty()) {
            page.section("我的线路")
            val card = page.card()
            pinned.forEach { row ->
                NearbyLineRowView.add(
                    card, row.line, row.station.name, row.station.distance, true
                ) { toggle(row) }
            }
        }

        val shownKeys = pinned.map { it.line.lineId to it.line.direction }.toSet()
        val missing = prefs.lines
            .distinctBy { it.lineId to it.direction }
            .filter { (it.lineId to it.direction) !in shownKeys }
        if (d != null && missing.isNotEmpty()) {
            page.section("我的订阅")
            val card = page.card()
            missing.forEach { sub ->
                ListRow.add(card, "★", sub.title, sub.stationName.ifBlank { "附近未发现" }) {
                    startActivity(Intent(this, LinesActivity::class.java))
                }
            }
            page.hint("这些线路不在附近；点线路可管理订阅。")
        }

        if (nearby.isNotEmpty()) {
            page.section("附近线路")
            val card = page.card()
            nearby.forEach { row ->
                NearbyLineRowView.add(
                    card, row.line, row.station.name, row.station.distance, false
                ) { subscribe(row) }
            }
        } else if (d != null && d.stations.isEmpty()) {
            page.section("附近线路")
            val card = page.card()
            ListRow.add(card, "🚏", "附近没有站点", "移动一下或切换城市", chevron = false)
        } else if (d != null && rows.isEmpty()) {
            page.section("附近线路")
            val card = page.card()
            ListRow.add(card, "🚌", "暂无线路数据", "换个站点或刷新试试", chevron = false)
        }

        if (d != null && d.stations.isNotEmpty()) {
            page.button("查看全部附近站点", primary = false) {
                startActivity(
                    Intent(this, NearbyLinesActivity::class.java)
                        .putExtra(NearbyLinesActivity.EXTRA_USE_CURRENT, true)
                )
            }
            if (BuildConfig.AMAP_KEY.isNotBlank()) {
                page.button("在地图上查看", primary = false) {
                    startActivity(Intent(this, NearbyMapActivity::class.java))
                }
            }
        }

        renderActions()
        page.hint("按到站时间排序；点线路可订阅/取消。订阅只看线路 + 方向，不绑地点。")
    }

    private fun renderStatus() {
        val card = page.card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }

        row.addView(TextView(this).apply {
            text = "📍"
            textSize = 18f
        })

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val d = data
        val title = when {
            loading -> phase
            d != null -> d.label + if (accuracy > 0) " · 精度 ${accuracy.toInt()}m" else ""
            !hasLocationPermission() -> "定位权限未开启"
            lastError != null -> "查询失败"
            else -> "准备查询"
        }
        val detail = when {
            loading && d != null -> "上次结果 · ${timeText(d.fetchedAt)}"
            loading -> "正在获取附近站点…"
            d != null -> buildString {
                val count = NearbySelector.build(d.stations, d.linesByStation, prefs.lines).size
                append("${d.stations.size} 个站点 · $count 条线路")
                if (d.partial) append(" · 部分站点加载失败")
                append(" · 更新 ").append(timeText(d.fetchedAt))
                if (!lastError.isNullOrBlank()) append(" · 上次失败：").append(lastError)
            }
            !hasLocationPermission() -> "开启定位后，打开 App 会自动查询附近公交"
            lastError != null -> lastError.orEmpty()
            else -> "点刷新开始查询"
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
        })
        texts.addView(TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)

        if (loading) {
            row.addView(ProgressBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
        }
        val refresh = MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "刷新"
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            isEnabled = !loading
            setOnClickListener {
                if (hasLocationPermission()) load(force = true) else maybeStart()
            }
        }
        row.addView(refresh, LinearLayout.LayoutParams(-2, dp(40)))
        card.addView(row)

        if (d == null && !loading && !hasLocationPermission()) {
            page.button("打开应用设置") { openAppSettings() }
            if (firstValidPlace() != null) {
                page.button("用常去地点查询", primary = false) {
                    firstValidPlace()?.let { loadFromPlace(it) }
                }
            } else {
                page.button("添加常去地点", primary = false) {
                    startActivity(Intent(this, PlacesActivity::class.java))
                }
            }
        } else if (d == null && !loading && lastError != null) {
            page.button("重试") { load(force = true) }
        }
    }

    private fun renderActions() {
        page.section("更多")
        val card = page.card()
        ListRow.add(card, "🚌", "订阅线路", "${prefs.lines.size} 条") {
            startActivity(Intent(this, LinesActivity::class.java))
        }
        ListRow.add(card, "🚏", "附近公交", "按站点查看") {
            startActivity(
                Intent(this, NearbyLinesActivity::class.java)
                    .putExtra(NearbyLinesActivity.EXTRA_USE_CURRENT, true)
            )
        }
        ListRow.add(card, "📍", "常去地点", "${prefs.places.size} 个") {
            startActivity(Intent(this, PlacesActivity::class.java))
        }
        ListRow.add(card, "🏙", "城市", CityPicker.label(prefs)) {
            CityPicker.show(this, prefs) {
                render()
                if (data == null) maybeStart()
            }
        }
        ListRow.add(card, "⚙️", "设置") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ------------------------------------------------------------ 订阅

    private fun subscribe(row: NearbyLineRow) {
        val d = data ?: return
        val line = row.line
        val station = row.station
        val sub = LineSub(
            cityId = d.cityId,
            lineId = line.lineId,
            lineName = line.lineName,
            lineNo = line.lineNo,
            direction = line.direction,
            targetOrder = line.order.coerceAtLeast(1),
            stationName = line.stationName.ifBlank { station.name },
            stationId = line.sId.ifBlank { station.sId },
            place = "",
            startSn = line.startSn,
            endSn = line.endSn
        )
        if (prefs.addLine(sub)) {
            toast("已订阅，已置顶")
        } else {
            toast("已订阅")
        }
        render()
    }

    private fun toggle(row: NearbyLineRow) {
        val sub = row.subscription ?: return
        AlertDialog.Builder(this)
            .setTitle("${sub.title} · ${row.station.name}")
            .setMessage("取消订阅这条线路？")
            .setPositiveButton("取消订阅") { _, _ ->
                prefs.removeLine(sub.lineId, sub.direction)
                render()
                toast("已取消订阅")
            }
            .setNegativeButton("保留", null)
            .show()
    }

    // ------------------------------------------------------------ 小工具

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast("没有找到应用设置") }
    }

    private fun attrColor(attr: Int, fallback: Int = android.graphics.Color.BLACK): Int =
        com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

    private fun timeText(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
