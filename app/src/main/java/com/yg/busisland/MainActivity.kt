package com.yg.busisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/**
 * 首页：打开即定位，直接列附近线路；订阅行置顶。
 * 不启动服务、不申请通知权限，也不做后台轮询。
 */
class MainActivity : com.yg.busisland.ui.PageActivity() {

    companion object {
        private const val REQ_LOCATION = 100
        private const val FRESH_MS = 30_000L
        private const val ETA_REFRESH_MS = 20_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold
    private val loader = NearbyLoader()

    // 首页「下一班」：选一条订阅线路（优先已设置上车站的），20 秒刷新。
    private val etaExecutor = Executors.newSingleThreadExecutor()
    private val etaHandler = Handler(Looper.getMainLooper())
    private var etaSub: LineSub? = null
    private var etaResult: RealTimeResult? = null
    private var etaError: String? = null
    private var etaLoading = false
    private var etaKey = ""
    private var etaRequest = 0
    private var etaFetchedAt = 0L
    private var etaFetchedWall = 0L

    private var etaLineView: TextView? = null
    private var etaStationView: TextView? = null
    private var etaBigView: TextView? = null
    private var etaMetaView: TextView? = null
    private var etaStateView: TextView? = null

    private val etaTick = object : Runnable {
        override fun run() {
            fetchEta(force = true)
            etaHandler.postDelayed(this, ETA_REFRESH_MS)
        }
    }

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
        etaHandler.removeCallbacks(etaTick)
        etaHandler.postDelayed(etaTick, 800)
        if (!started || loading) return
        val stale = data == null || SystemClock.elapsedRealtime() - lastLoadAt >= FRESH_MS
        if (stale) maybeStart()
    }

    override fun onPause() {
        etaHandler.removeCallbacks(etaTick)
        super.onPause()
    }

    override fun onDestroy() {
        etaHandler.removeCallbacksAndMessages(null)
        etaExecutor.shutdownNow()
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

        LocationProvider.best(this) { fix ->
            if (id != requestId || isFinishing || isDestroyed) return@best
            if (fix != null) {
                startQuery(
                    lat = fix.lat,
                    lng = fix.lng,
                    label = labelFor(fix),
                    accuracy = fix.accuracyM,
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

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun labelFor(fix: LocationFix): String = when {
        !hasFineLocation() -> "大致位置"
        fix.source == LocationFix.Source.SYSTEM -> "系统定位"
        else -> "当前位置"
    }

    private fun firstValidPlace(): Place? = prefs.places.firstOrNull { it.valid }

    // ------------------------------------------------------------ 下一班

    private fun primaryEtaSub(): LineSub? {
        val lines = prefs.lines
        return lines.firstOrNull { it.targetOrder > 0 && it.stationId.isNotBlank() }
            ?: lines.firstOrNull()
    }

    private fun etaKeyOf(sub: LineSub): String =
        "${sub.cityId}|${sub.lineId}|${sub.direction}|${sub.targetOrder}|${sub.stationId}"

    private fun fetchEta(force: Boolean) {
        val sub = primaryEtaSub()
        if (sub == null) {
            etaSub = null
            etaResult = null
            etaError = null
            etaLoading = false
            etaKey = ""
            etaFetchedWall = 0L
            refreshEtaCard()
            return
        }
        val key = etaKeyOf(sub)
        if (!force && etaKey == key &&
            SystemClock.elapsedRealtime() - etaFetchedAt < ETA_REFRESH_MS
        ) return
        if (etaLoading && etaKey == key) return

        if (etaKey != key) {
            etaResult = null
            etaFetchedWall = 0L
        }
        etaKey = key
        etaSub = sub
        etaError = null
        if (sub.targetOrder <= 0) {
            etaLoading = false
            etaResult = null
            etaFetchedWall = 0L
            refreshEtaCard()
            return
        }

        etaLoading = true
        refreshEtaCard()
        val id = ++etaRequest
        val lat = data?.lat
        val lng = data?.lng
        val udid = prefs.udid
        etaExecutor.execute {
            val result = runCatching { BusApiClient.realtime(sub, udid, lat, lng) }
            runOnUiThread {
                if (id != etaRequest || isFinishing || isDestroyed) return@runOnUiThread
                etaLoading = false
                result.onSuccess {
                    etaResult = it
                    etaError = null
                    etaFetchedAt = SystemClock.elapsedRealtime()
                    etaFetchedWall = System.currentTimeMillis()
                }.onFailure {
                    etaError = it.message ?: "实时到站查询失败"
                }
                refreshEtaCard()
            }
        }
    }

    private fun renderNextBus() {
        val sub = primaryEtaSub()
        page.section("下一班")
        val card = page.card()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            val out = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
            isClickable = true
            setOnClickListener {
                val current = primaryEtaSub() ?: return@setOnClickListener
                LineDetailSheet.show(this@MainActivity, LineDetailTarget.from(current)) {
                    fetchEta(force = true)
                    render()
                }
            }
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val lineView = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
        }
        etaLineView = lineView
        val stationView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(3), 0, 0)
        }
        etaStationView = stationView
        val bigView = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            gravity = Gravity.END
            maxLines = 1
            setPadding(dp(12), 0, 0, 0)
        }
        etaBigView = bigView
        left.addView(lineView)
        left.addView(stationView)
        topRow.addView(left)
        topRow.addView(bigView, LinearLayout.LayoutParams(-2, -2))
        body.addView(topRow)

        val metaView = TextView(this).apply {
            textSize = 12f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            maxLines = 2
            setPadding(0, dp(6), 0, 0)
        }
        etaMetaView = metaView
        body.addView(metaView)

        val stateView = TextView(this).apply {
            textSize = 11f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(3), 0, 0)
        }
        etaStateView = stateView
        body.addView(stateView)
        card.addView(body)

        if (sub == null) {
            page.hint("订阅线路后，这里会显示下一班还有几分钟。")
        } else if (sub.targetOrder <= 0 || sub.stationId.isBlank()) {
            page.hint("这条线路还没有上车站，点卡片选择后就能看到倒计时。")
        }

        refreshEtaCard()
        if (sub != null && etaKeyOf(sub) != etaKey) {
            fetchEta(force = true)
        }
    }

    private fun refreshEtaCard() {
        val lineView = etaLineView ?: return
        val sub = etaSub ?: primaryEtaSub()
        if (sub == null) {
            lineView.text = "还没有订阅线路"
            etaStationView?.text = "点下面「订阅线路」添加常坐线路"
            etaBigView?.text = "—"
            etaMetaView?.text = ""
            etaStateView?.text = ""
            return
        }
        lineView.text = sub.title
        etaStationView?.text = "上车站 · " + sub.stationName.ifBlank { "未设置" }

        val result = etaResult
        val bus = result?.firstBus()
        val big = when {
            sub.targetOrder <= 0 || sub.stationId.isBlank() -> "—"
            etaLoading && result == null -> "…"
            etaError != null && result == null -> "失败"
            bus != null -> bus.etaText
            result != null && (result.lineDesc.contains("不在运营") ||
                result.lineDesc.contains("停运")) -> "停运"
            result != null -> "暂无车"
            else -> "…"
        }
        etaBigView?.text = big

        etaMetaView?.text = when {
            bus != null -> buildString {
                if (bus.clock.isNotBlank()) append("预计 ").append(bus.clock)
                val second = result.buses.getOrNull(1)
                if (second != null) {
                    if (isNotEmpty()) append(" · ")
                    append("后一班 ").append(second.etaText)
                }
                if (bus.distance > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("距站 ").append(metersText(bus.distance))
                }
                if (isNotEmpty()) append(" · ")
                append(result.buses.size).append(" 辆车")
            }
            sub.targetOrder <= 0 || sub.stationId.isBlank() -> "先选上车站"
            result != null && result.lineDesc.isNotBlank() -> result.lineDesc
            !result?.nextOperationDesc.isNullOrBlank() -> result.nextOperationDesc
            etaError != null -> etaError.orEmpty()
            else -> "正在查实时到站…"
        }

        etaStateView?.text = when {
            etaError != null -> "查询失败，20 秒后重试"
            etaFetchedWall > 0 -> "更新 ${clockText(etaFetchedWall)} · 20 秒自动刷新"
            else -> "20 秒自动刷新"
        }
    }

    private fun metersText(meters: Int): String =
        if (meters < 1000) "${meters}m"
        else String.format(Locale.getDefault(), "%.1fkm", meters / 1000.0)

    private fun clockText(at: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

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
        renderNextBus()

        val d = data
        val rows = if (d != null) {
            NearbySelector.build(d.stations, d.linesByStation, prefs.lines)
        } else {
            emptyList()
        }
        val pinned = rows.filter { it.subscription != null }
        val nearby = rows.filter { it.subscription == null }
        val pinnedByKey = pinned.associateBy { it.line.lineId to it.line.direction }
        val mine = prefs.lines.distinctBy { it.lineId to it.direction }
        var missingCount = 0

        if (mine.isNotEmpty()) {
            page.section("我的线路")
            val card = page.card()
            mine.forEach { sub ->
                val row = pinnedByKey[sub.lineId to sub.direction]
                if (row != null) {
                    NearbyLineRowView.add(
                        card, row.line, row.boardingName, row.boardingDistanceM, true
                    ) {
                        LineDetailSheet.show(
                            this,
                            LineDetailTarget.from(row, d?.cityId.orEmpty())
                        ) { render() }
                    }
                } else {
                    missingCount++
                    val label = when {
                        d == null && loading -> "正在查附近…"
                        d == null -> "附近数据不可用"
                        sub.stationName.isBlank() -> "附近未发现"
                        else -> "附近未发现 · ${sub.stationName}"
                    }
                    val value = ListRow.add(card, "★", sub.title, label) {
                        LineDetailSheet.show(this, LineDetailTarget.from(sub)) { render() }
                    }
                    value.setTextColor(
                        attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                }
            }
            if (missingCount > 0) {
                page.hint(
                    if (d == null) "订阅已保留；附近数据恢复后会显示到站时间。"
                    else "「附近未发现」表示该方向当前不在附近；点进去可管理订阅。"
                )
            }
        }

        if (nearby.isNotEmpty()) {
            page.section("附近线路")
            val card = page.card()
            nearby.forEach { row ->
                NearbyLineRowView.add(
                    card, row.line, row.boardingName, row.boardingDistanceM, false
                ) {
                    LineDetailSheet.show(
                        this,
                        LineDetailTarget.from(row, d?.cityId.orEmpty())
                    ) { render() }
                }
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
        page.hint("点线路查看详情；订阅在详情页里确认。订阅只看线路 + 方向，不绑地点。")
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
                if (accuracy > LocationFix.COARSE_ACCURACY_M) append(" · 定位较粗，站点可能不准")
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

        if (hasLocationPermission() && !hasFineLocation()) {
            page.button("开启精确位置", primary = false) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_LOCATION
                )
            }
        }

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
