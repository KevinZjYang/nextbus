package com.yg.busisland

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.yg.busisland.ui.PageActivity
import com.yg.busisland.ui.PageScaffold
import com.yg.busisland.ui.colorOnSurface
import com.yg.busisland.ui.colorOnSurfaceVariant
import com.yg.busisland.ui.colorPrimary
import com.yg.busisland.ui.colorSurface
import com.yg.busisland.ui.colorSurfaceVariant
import com.yg.busisland.ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 地图页：显示最近一次附近查询的站点，并绘制选中线路的走向。
 *
 * 数据来自 NearbySnapshot（首页/附近页查询后写入），不通过 Intent 传大对象。
 * 地图只在当前页面使用，不启动后台定位，也不改订阅/地点数据模型。
 */
class NearbyMapActivity : PageActivity() {

    companion object {
        private const val MAP_LOAD_TIMEOUT_MS = 8_000L
    }

    private lateinit var prefs: Prefs
    private val loader = NearbyLoader()
    private val main = Handler(Looper.getMainLooper())
    private val routeExecutor = Executors.newSingleThreadExecutor()

    private var data: NearbyData? = null
    private var rows: List<NearbyLineRow> = emptyList()
    private var selectedKey = ""
    private var selectedRoute: LineRoute? = null
    private var routePoints: List<GeoPoint> = emptyList()
    private var routeStatus = ""
    private var routeLoading = false
    private var routeRequest = 0
    private val routeCache = HashMap<String, LineRoute>()

    private var mapView: TextureMapView? = null
    private var map: AMap? = null
    private var mapCreated = false
    private var mapReady = false
    private var mapLoadCheck: Runnable? = null
    private var mapRetry = 0
    private val markerStations = HashMap<String, NearbyStation>()

    private lateinit var root: FrameLayout
    private lateinit var topCard: MaterialCardView
    private lateinit var bottomCard: MaterialCardView
    private lateinit var topSubtitle: TextView
    private lateinit var refreshButton: MaterialButton
    private lateinit var mapLoading: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var subscribeButton: MaterialButton
    private lateinit var chipsRow: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var lookAllButton: MaterialButton
    private lateinit var backNearButton: MaterialButton

    private var loading = false
    private var lastStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.dedupeLinesOnce()

        data = NearbySnapshot.current()
        if (data == null) {
            showFallback("附近数据已失效", "先回首页查询附近线路，再打开地图。")
            return
        }
        if (BuildConfig.AMAP_KEY.isBlank()) {
            showFallback("未配置高德 Key", "在 local.properties 配置 amap.key 后重新构建，地图入口才会显示。")
            return
        }
        selectedKey = savedInstanceState?.getString("selected_line").orEmpty()
        buildMapUi(savedInstanceState)
        if (mapView != null) {
            rebuildRows()
            renderPanel()
        }
    }

    // ------------------------------------------------------------ 页面骨架

    private fun buildMapUi(savedInstanceState: Bundle?) {
        root = FrameLayout(this).apply {
            setBackgroundColor(colorSurfaceVariant())
        }

        val mv = try {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            TextureMapView(this)
        } catch (t: Throwable) {
            showFallback("地图初始化失败", t.message ?: "高德地图组件不可用，请检查依赖与 Key。")
            return
        }
        mapView = mv
        root.addView(mv, FrameLayout.LayoutParams(-1, -1))

        topCard = buildTopCard()
        root.addView(
            topCard,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                topMargin = Ui.statusBarHeight(this@NearbyMapActivity) + dp(8)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )

        bottomCard = buildBottomCard()
        root.addView(
            bottomCard,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )

        mapLoading = TextView(this).apply {
            text = "地图加载中…"
            textSize = 13f
            setTextColor(colorOnSurface())
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = Ui.pill(colorSurface(), dp(20))
        }
        root.addView(
            mapLoading,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        )

        setContentView(root)
        applyInsets()
        mv.onCreate(savedInstanceState)
        mapCreated = true
        setupMap()
    }

    private fun buildTopCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(colorSurface())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        row.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "‹"
                textSize = 26f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "地图"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface())
        })
        topSubtitle = TextView(this).apply {
            text = "附近站点与线路走向"
            textSize = 11.5f
            setTextColor(colorOnSurfaceVariant())
            setPadding(0, dp(1), 0, 0)
        }
        texts.addView(topSubtitle)
        row.addView(texts)

        refreshButton = MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "刷新"
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { refresh() }
        }
        row.addView(refreshButton, LinearLayout.LayoutParams(-2, dp(40)))
        card.addView(row)
        return card
    }

    private fun buildBottomCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(colorSurface())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        detailTitle = TextView(this).apply {
            text = "暂无可显示线路"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface())
        }
        detailMeta = TextView(this).apply {
            text = "附近没有站点"
            textSize = 12f
            setTextColor(colorOnSurfaceVariant())
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(detailTitle)
        texts.addView(detailMeta)
        detailRow.addView(texts)

        subscribeButton = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "订阅线路"
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            cornerRadius = dp(18)
            setPadding(dp(14), 0, dp(14), 0)
        }
        detailRow.addView(subscribeButton, LinearLayout.LayoutParams(-2, dp(38)))
        content.addView(detailRow)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(9), 0, 0)
        }
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(chipsRow)
        content.addView(scroll)

        statusView = TextView(this).apply {
            text = "点站点标记看经过线路；点线路条切换走向"
            textSize = 11.5f
            setTextColor(colorOnSurfaceVariant())
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(statusView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        lookAllButton = actionButton("看全线") { fitRoute() }
        backNearButton = actionButton("回到附近") { fitNearby(true) }
        actions.addView(lookAllButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        actions.addView(backNearButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        content.addView(actions)

        card.addView(content)
        return card
    }

    private fun actionButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (topCard.layoutParams as? FrameLayout.LayoutParams)?.topMargin = bars.top + dp(8)
            (bottomCard.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = bars.bottom + dp(10)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupMap() {
        val m = mapView?.map
        if (m == null) {
            if (++mapRetry <= 5) {
                mapView?.postDelayed({ setupMap() }, 300)
            } else {
                showFallback("地图组件未就绪", "请检查网络和高德 Key 后重试。")
            }
            return
        }
        map = m
        m.mapType = AMap.MAP_TYPE_NORMAL
        m.uiSettings.setZoomControlsEnabled(false)
        m.uiSettings.setCompassEnabled(false)
        m.uiSettings.setRotateGesturesEnabled(false)
        m.uiSettings.setTiltGesturesEnabled(false)
        m.uiSettings.setMyLocationButtonEnabled(false)
        m.uiSettings.setScaleControlsEnabled(false)
        m.setOnMarkerClickListener { marker ->
            val station = markerStations[marker.id]
            if (station != null) {
                showStationLines(station)
                true
            } else {
                false
            }
        }
        m.setOnMapLoadedListener {
            mapReady = true
            mapLoading.visibility = View.GONE
            drawAll()
            fitNearby(false)
            renderPanel()
        }
        mapLoadCheck = Runnable {
            if (!mapReady && !isFinishing && !isDestroyed) {
                showFallback("地图加载超时", "检查网络和高德 Key 后重试；仍可回附近页看列表。")
            }
        }
        main.postDelayed(mapLoadCheck!!, MAP_LOAD_TIMEOUT_MS)
    }

    private fun showFallback(title: String, message: String) {
        mapLoadCheck?.let { main.removeCallbacks(it) }
        mapLoadCheck = null
        if (mapCreated) {
            runCatching { mapView?.onPause() }
            runCatching { mapView?.onDestroy() }
        }
        mapView = null
        map = null
        mapCreated = false
        mapReady = false

        val page = PageScaffold(
            this, "地图", title,
            showBack = true,
            onBack = { finish() }
        )
        page.hint(message)
        page.button("返回") { finish() }
        setContentView(page.root)
    }

    // ------------------------------------------------------------ 线路选择

    private fun key(row: NearbyLineRow): String = "${row.line.lineId}:${row.line.direction}"

    private fun selectedRow(): NearbyLineRow? =
        rows.firstOrNull { key(it) == selectedKey }

    private fun rebuildRows() {
        val d = data ?: return
        rows = NearbySelector.build(d.stations, d.linesByStation, prefs.lines, maxRows = 60)
        val current = selectedRow()
        if (current == null) {
            selectedKey = rows.firstOrNull()?.let { key(it) }.orEmpty()
            selectedRoute = null
            routePoints = emptyList()
            routeRequest++
            routeLoading = false
            routeStatus = when {
                rows.isEmpty() -> "附近没有可显示的线路"
                else -> ""
            }
            rows.firstOrNull()?.let { loadRoute(it) }
        } else {
            val cached = routeCache[selectedKey]
            if (cached != null) {
                selectedRoute = cached
                routePoints = cached.points
                routeStatus = "线路走向 · ${cached.points.size} 个点"
            } else if (!routeLoading) {
                loadRoute(current)
            }
        }
        renderPanel()
        drawAll()
    }

    private fun selectLine(row: NearbyLineRow) {
        val k = key(row)
        if (k == selectedKey) {
            if (selectedRoute == null && !routeLoading) loadRoute(row)
            renderPanel()
            drawAll()
            return
        }
        selectedKey = k
        selectedRoute = routeCache[k]
        routePoints = selectedRoute?.points.orEmpty()
        routeStatus = selectedRoute?.let { "线路走向 · ${it.points.size} 个点" }.orEmpty()
        if (selectedRoute == null) {
            loadRoute(row)
        } else {
            renderPanel()
            drawAll()
        }
    }

    private fun loadRoute(row: NearbyLineRow) {
        val d = data ?: return
        val k = key(row)
        val city = d.cityId.ifBlank { prefs.cityId }
        val id = ++routeRequest
        routeLoading = true
        selectedRoute = null
        routePoints = emptyList()
        routeStatus = "加载线路走向…"
        renderPanel()

        routeExecutor.execute {
            val result = runCatching {
                BusApiClient.lineRoute(city, row.line.lineId, row.line.lineName, prefs.udid)
            }
            main.post {
                if (id != routeRequest || isFinishing || isDestroyed) return@post
                routeLoading = false
                result.onSuccess { r ->
                    if (selectedKey != k) return@onSuccess
                    if (r.points.size < 2) {
                        routeStatus = "走向点不足（仅显示站点）"
                    } else {
                        routeCache[k] = r
                        selectedRoute = r
                        routePoints = r.points
                        routeStatus = "线路走向 · ${r.points.size} 个点"
                        drawAll()
                    }
                }.onFailure { e ->
                    if (selectedKey == k) {
                        routeStatus = "线路走向加载失败：${e.message ?: "未知错误"}"
                    }
                }
                renderPanel()
            }
        }
    }

    // ------------------------------------------------------------ 地图绘制

    private fun drawAll() {
        val m = map ?: return
        if (!mapReady) return
        val d = data ?: return
        m.clear()
        markerStations.clear()

        m.addMarker(
            MarkerOptions()
                .position(LatLng(d.lat, d.lng))
                .title(d.label)
                .snippet("查询中心")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        d.stations.forEach { st ->
            if (!valid(st.lat, st.lng)) return@forEach
            val lineCount = d.linesByStation[st.sId].orEmpty().size
            val marker = m.addMarker(
                MarkerOptions()
                    .position(gcj(st.lat, st.lng))
                    .title(st.name)
                    .snippet(
                        buildString {
                            if (st.distance >= 0) append("${st.distance}m · ")
                            append("$lineCount 条线路")
                        }
                    )
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            ) ?: return@forEach
            markerStations[marker.id] = st
        }

        val row = selectedRow()
        if (row != null && valid(row.station.lat, row.station.lng)) {
            m.addMarker(
                MarkerOptions()
                    .position(gcj(row.station.lat, row.station.lng))
                    .title("上车站 · ${row.station.name}")
                    .snippet(row.line.title)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
        }

        val r = selectedRoute
        routePoints = r?.points.orEmpty()
        if (r != null && r.points.size >= 2) {
            val points = r.points.map { gcj(it.lat, it.lng) }
            m.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(dp(14).toFloat())
                    .color(0xCCFFFFFF.toInt())
                    .zIndex(2f)
            )
            m.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(dp(8).toFloat())
                    .color(colorPrimary())
                    .zIndex(3f)
            )
        }
    }

    private fun fitNearby(animate: Boolean) {
        val m = map ?: return
        val d = data ?: return
        val points = ArrayList<LatLng>()
        if (valid(d.lat, d.lng)) points += LatLng(d.lat, d.lng)
        d.stations.filter { valid(it.lat, it.lng) }.forEach { points += gcj(it.lat, it.lng) }
        if (points.isEmpty()) return
        val update = when {
            points.size == 1 -> CameraUpdateFactory.newLatLngZoom(points[0], 16f)
            else -> {
                val builder = LatLngBounds.Builder()
                points.forEach { builder.include(it) }
                CameraUpdateFactory.newLatLngBounds(builder.build(), dp(110))
            }
        }
        if (animate) m.animateCamera(update) else m.moveCamera(update)
    }

    private fun fitRoute() {
        val m = map ?: return
        if (routePoints.size < 2) {
            toast("这条线路还没有走向数据")
            return
        }
        val builder = LatLngBounds.Builder()
        routePoints.forEach { builder.include(gcj(it.lat, it.lng)) }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), dp(70)))
    }

    private fun showStationLines(station: NearbyStation) {
        val lines = data?.linesByStation?.get(station.sId).orEmpty()
        if (lines.isEmpty()) {
            toast("这个站点暂无线路数据")
            return
        }
        val labels = lines.map { line ->
            buildString {
                append(line.title)
                if (line.towards.isNotBlank()) append(" · ").append(line.towards)
                if (line.etaText.isNotBlank()) append(" · ").append(line.etaText)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${station.name} · 经过线路")
            .setItems(labels) { _, index ->
                val line = lines[index]
                val existing = rows.firstOrNull {
                    it.line.lineId == line.lineId && it.line.direction == line.direction
                }
                val row = existing ?: NearbyLineRow(
                    station,
                    line,
                    prefs.lines.firstOrNull {
                        it.lineId == line.lineId && it.direction == line.direction
                    }
                ).also { rows = rows + it }
                selectLine(row)
            }
            .show()
    }

    // ------------------------------------------------------------ 订阅与刷新

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
        toast(if (prefs.addLine(sub)) "已订阅，已置顶" else "已订阅")
        rebuildRows()
    }

    private fun toggle(row: NearbyLineRow) {
        val sub = row.subscription ?: return
        AlertDialog.Builder(this)
            .setTitle("${sub.title} · ${row.station.name}")
            .setMessage("取消订阅这条线路？")
            .setPositiveButton("取消订阅") { _, _ ->
                prefs.removeLine(sub.lineId, sub.direction)
                rebuildRows()
                toast("已取消订阅")
            }
            .setNegativeButton("保留", null)
            .show()
    }

    private fun refresh() {
        if (loading) return
        val d = data ?: return
        loading = true
        lastStatus = ""
        renderPanel()
        loader.load(
            cityId = d.cityId,
            cityName = d.cityName,
            autoCity = d.cityId.isBlank(),
            lat = d.lat,
            lng = d.lng,
            label = d.label,
            udid = prefs.udid,
            maxStations = 8,
            maxLines = 6,
            timeoutMs = 10_000
        ) { result ->
            if (isFinishing || isDestroyed) return@load
            loading = false
            result.onSuccess { nd ->
                data = nd
                NearbySnapshot.put(nd)
                nd.detectedCity?.let { c ->
                    if (c.id != prefs.cityId || c.name != prefs.cityName) prefs.saveCity(c)
                }
                if (nd.cityId != d.cityId) routeCache.clear()
                rebuildRows()
                fitNearby(false)
                drawAll()
                renderPanel()
            }.onFailure { e ->
                lastStatus = "刷新失败：${e.message ?: "未知错误"}"
                renderPanel()
                toast(lastStatus)
            }
        }
    }

    // ------------------------------------------------------------ 底部面板

    private fun renderPanel() {
        val d = data
        topSubtitle.text = if (d == null) {
            "附近数据不可用"
        } else {
            buildString {
                append(d.label)
                append(" · ")
                append(d.stations.size).append(" 站 / ").append(rows.size).append(" 线")
                if (d.partial) append(" · 部分失败")
                append(" · ").append(timeText(d.fetchedAt))
            }
        }
        refreshButton.isEnabled = !loading

        val row = selectedRow()
        if (row == null) {
            detailTitle.text = "暂无可显示线路"
            detailMeta.text = if (d == null) "附近数据不可用" else "附近没有站点或线路"
            subscribeButton.visibility = View.GONE
        } else {
            subscribeButton.visibility = View.VISIBLE
            val sub = row.subscription
            detailTitle.text = (if (sub != null) "★ " else "") + row.line.title
            detailMeta.text = buildString {
                append(row.station.name)
                if (row.station.distance >= 0) append(" · ").append(row.station.distance).append("m")
                if (row.line.towards.isNotBlank()) append(" · ").append(row.line.towards)
                append(" · ").append(row.line.etaText.ifBlank { "—" })
            }
            subscribeButton.text = if (sub == null) "订阅线路" else "已订阅"
            subscribeButton.backgroundTintList = ColorStateList.valueOf(
                if (sub == null) colorPrimary() else colorSurfaceVariant()
            )
            subscribeButton.setTextColor(if (sub == null) Color.WHITE else colorOnSurface())
            subscribeButton.setOnClickListener {
                if (sub == null) subscribe(row) else toggle(row)
            }
        }

        chipsRow.removeAllViews()
        if (rows.isEmpty()) {
            chipsRow.addView(TextView(this).apply {
                text = "附近没有线路"
                textSize = 12.5f
                setTextColor(colorOnSurfaceVariant())
                setPadding(dp(4), dp(2), dp(4), dp(2))
            })
        } else {
            rows.forEach { row ->
                val selected = key(row) == selectedKey
                val chip = MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = buildString {
                        if (row.subscription != null) append("★ ")
                        append(row.line.title)
                        if (row.line.towards.isNotBlank()) append(" ").append(row.line.towards)
                    }
                    textSize = 12.5f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    cornerRadius = dp(18)
                    setPadding(dp(12), 0, dp(12), 0)
                    backgroundTintList = ColorStateList.valueOf(
                        if (selected) colorPrimary() else colorSurface()
                    )
                    setTextColor(if (selected) Color.WHITE else colorOnSurface())
                    strokeWidth = if (selected) 0 else dp(1)
                    strokeColor = ColorStateList.valueOf(colorSurfaceVariant())
                    setOnClickListener { selectLine(row) }
                }
                chipsRow.addView(
                    chip,
                    LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) }
                )
            }
        }

        statusView.text = when {
            loading -> "刷新中…"
            routeLoading -> routeStatus
            lastStatus.isNotBlank() -> lastStatus
            routeStatus.isNotBlank() -> routeStatus
            else -> "点站点标记看经过线路；点线路条切换走向"
        }
        lookAllButton.isEnabled = routePoints.size >= 2
        backNearButton.isEnabled = d?.stations?.any { valid(it.lat, it.lng) } == true
    }

    // ------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapLoadCheck?.let { main.removeCallbacks(it) }
        mapLoadCheck = null
        loader.close()
        routeExecutor.shutdownNow()
        if (mapCreated) {
            runCatching { mapView?.onDestroy() }
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected_line", selectedKey)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    // ------------------------------------------------------------ 小工具

    private fun gcj(lat: Double, lng: Double): LatLng {
        val p = Gcj.toGcj(lat, lng)
        return LatLng(p[0], p[1])
    }

    private fun valid(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() &&
            kotlin.math.abs(lat) <= 90.0 && kotlin.math.abs(lng) <= 180.0 &&
            !(lat == 0.0 && lng == 0.0)

    private fun timeText(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
