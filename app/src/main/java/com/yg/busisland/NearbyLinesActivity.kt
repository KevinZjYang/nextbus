package com.yg.busisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.NearbyLineRowView
import com.yg.busisland.ui.PageActivity
import com.yg.busisland.ui.PageScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 附近公交详情页：按站点分组，点线路直接订阅/取消。 */
class NearbyLinesActivity : PageActivity() {

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_USE_CURRENT = "use_current"
        const val MAX_STATIONS = 6
        const val MAX_LINES = 5
    }

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold
    private val loader = NearbyLoader()

    private var place: Place? = null
    private var useCurrent = true
    private var data: NearbyData? = null
    private var loading = false
    private var status = "定位中…"
    private var accuracy = -1f
    private var requestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.dedupeLinesOnce()

        val placeId = intent.getStringExtra(EXTRA_PLACE_ID)
        useCurrent = intent.getBooleanExtra(EXTRA_USE_CURRENT, false)
        place = if (!placeId.isNullOrBlank()) prefs.placeById(placeId) else null

        val subtitle = if (!useCurrent && place != null) {
            place!!.name.ifBlank { "地点" }
        } else {
            "当前位置"
        }
        page = PageScaffold(this, "附近公交", subtitle, showBack = true, onBack = { finish() })
        setContentView(page.root)
        render()
        start()
    }

    override fun onDestroy() {
        loader.close()
        super.onDestroy()
    }

    private fun start() {
        val p = place
        if (!useCurrent && p != null && p.valid) {
            loadFrom(p.lat, p.lng, p.name.ifBlank { "地点" }, p.cityId.ifBlank { null }, p.cityId.isBlank())
        } else {
            resolveLocation()
        }
    }

    private fun resolveLocation() {
        if (!hasLocationPermission()) {
            val p = prefs.places.firstOrNull { it.valid }
            if (p != null) {
                loadFrom(p.lat, p.lng, p.name.ifBlank { "地点" } + "（缓存）", p.cityId.ifBlank { null }, p.cityId.isBlank())
            } else {
                status = "定位权限未开启"
                render()
            }
            return
        }
        loading = true
        status = "定位中…"
        accuracy = -1f
        val id = ++requestId
        render()

        LocationProvider.best(this) { fix ->
            if (id != requestId || isFinishing || isDestroyed) return@best
            if (fix != null) {
                loadFrom(
                    fix.lat,
                    fix.lng,
                    labelFor(fix),
                    null,
                    true,
                    id,
                    fix.accuracyM
                )
            } else {
                val p = prefs.places.firstOrNull { it.valid }
                if (p != null) {
                    loadFrom(
                        p.lat, p.lng, p.name.ifBlank { "地点" } + "（缓存）",
                        p.cityId.ifBlank { null }, p.cityId.isBlank(), id
                    )
                } else {
                    loading = false
                    status = "拿不到位置，打开定位再试"
                    render()
                }
            }
        }
    }

    private fun labelFor(fix: LocationFix): String = when {
        !hasFineLocation() -> "大致位置"
        fix.source == LocationFix.Source.SYSTEM -> "系统定位"
        else -> "当前位置"
    }

    private fun loadFrom(
        lat: Double,
        lng: Double,
        label: String,
        cityId: String?,
        autoCity: Boolean,
        id: Int = ++requestId,
        accuracyM: Float = -1f
    ) {
        loading = true
        status = "查询中…"
        accuracy = accuracyM
        render()
        loader.load(
            cityId = cityId ?: prefs.cityId,
            cityName = prefs.cityName,
            autoCity = autoCity,
            lat = lat,
            lng = lng,
            label = label,
            udid = prefs.udid,
            maxStations = MAX_STATIONS,
            maxLines = MAX_LINES,
            timeoutMs = 10_000
        ) { result ->
            if (id != requestId || isFinishing || isDestroyed) return@load
            loading = false
            result.onSuccess { d ->
                data = d
                NearbySnapshot.put(d)
                d.detectedCity?.let { c ->
                    if (c.id != prefs.cityId || c.name != prefs.cityName) prefs.saveCity(c)
                }
                status = statusText(d)
                render()
            }.onFailure { e ->
                status = "查询失败：${e.message ?: "未知错误"}"
                render()
                toast(status)
            }
        }
    }

    private fun refresh() {
        if (loading) return
        val p = place
        if (!useCurrent && p != null && p.valid) {
            loadFrom(p.lat, p.lng, p.name.ifBlank { "地点" }, p.cityId.ifBlank { null }, p.cityId.isBlank())
        } else {
            resolveLocation()
        }
    }

    private fun render() {
        page.clearBody()
        page.bodyLabel?.text = when {
            loading -> status
            data != null -> data!!.label
            else -> if (!useCurrent && place != null) place!!.name.ifBlank { "地点" } else "当前位置"
        }
        renderStatus()

        val d = data
        val rows = if (d != null) {
            NearbySelector.build(d.stations, d.linesByStation, prefs.lines, maxRows = 60)
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
                    card, row.line, row.boardingName, row.boardingDistanceM, true
                ) {
                    LineDetailSheet.show(
                        this,
                        LineDetailTarget.from(row, d?.cityId.orEmpty())
                    ) { render() }
                }
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
            ListRow.add(card, "🚌", "暂无线路数据", "刷新试试", chevron = false)
        }

        if (d != null && d.stations.isNotEmpty() && BuildConfig.AMAP_KEY.isNotBlank()) {
            page.button("在地图上查看", primary = false) {
                startActivity(Intent(this, NearbyMapActivity::class.java))
            }
        }

        page.hint("点线路查看详情；订阅或取消都在详情页里完成。")
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
            loading -> status
            d != null -> d.label + if (accuracy > 0) " · 精度 ${accuracy.toInt()}m" else ""
            !hasLocationPermission() -> "定位权限未开启"
            else -> "附近公交"
        }
        val detail = when {
            loading && d != null -> "上次结果 · ${timeText(d.fetchedAt)}"
            loading -> "正在获取附近站点…"
            d != null -> buildString {
                append(status)
                if (d.partial) append(" · 部分站点加载失败")
                append(" · 更新 ").append(timeText(d.fetchedAt))
            }
            !hasLocationPermission() -> "开启定位，或用常去地点兜底"
            else -> status
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
            setOnClickListener { refresh() }
        }
        row.addView(refresh, LinearLayout.LayoutParams(-2, dp(40)))
        card.addView(row)

        if (d == null && !loading && !hasLocationPermission()) {
            page.button("打开应用设置") { openAppSettings() }
            if (prefs.places.any { it.valid }) {
                page.button("用常去地点查询", primary = false) {
                    prefs.places.firstOrNull { it.valid }?.let { p ->
                        loadFrom(
                            p.lat, p.lng, p.name.ifBlank { "地点" } + "（缓存）",
                            p.cityId.ifBlank { null }, p.cityId.isBlank()
                        )
                    }
                }
            }
        }
    }

    private fun statusText(d: NearbyData): String {
        val count = NearbySelector.build(d.stations, d.linesByStation, prefs.lines).size
        return "${d.stations.size} 个站点 · $count 条线路"
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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
