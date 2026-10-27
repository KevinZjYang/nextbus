package com.yg.busisland

import android.app.AlertDialog
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 附近公交：进页面直接把「站点 + 该站线路 + 到站时间」全部铺出来，不用再点。
 *  - EXTRA_PLACE_ID：用某个地点的坐标
 *  - EXTRA_USE_CURRENT：用实时定位（陌生地方）
 */
class NearbyLinesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_USE_CURRENT = "use_current"
        private const val MAX_STATIONS = 6
        private const val MAX_LINES = 5
    }

    private lateinit var prefs: Prefs
    private var placeId: String = ""
    private var place: Place? = null
    private var useCurrent = false

    private var lat = 0.0
    private var lng = 0.0
    private var targetPlaceId: String = ""

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        placeId = intent.getStringExtra(EXTRA_PLACE_ID).orEmpty()
        useCurrent = intent.getBooleanExtra(EXTRA_USE_CURRENT, false)
        place = prefs.placeById(placeId)
        buildUi()

        val p = place
        if (!useCurrent && p != null && p.valid) {
            lat = p.lat
            lng = p.lng
            targetPlaceId = p.id
            subtitle.text = p.name.ifBlank { "地点" }
            loadAll()
        } else {
            useCurrent = true
            subtitle.text = "当前位置"
            resolveLocation()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun attrColor(attr: Int, fallback: Int = Color.BLACK): Int =
        com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF2F2F7.toInt()))
            setPadding(0, Ui.statusBarHeight(this@NearbyLinesActivity), 0, 0)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(16), dp(4))
        }
        bar.addView(com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "‹"
            textSize = 26f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "附近公交"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
        })
        subtitle = TextView(this).apply {
            text = "当前位置"
            textSize = 12f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        titleBox.addView(subtitle)
        bar.addView(titleBox)
        page.addView(bar)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(4))
        }
        status = TextView(this).apply {
            textSize = 12.5f
            text = "定位中…"
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        statusRow.addView(status)
        statusRow.addView(progress)
        page.addView(statusRow)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(28))
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(page)
    }

    // ------------------------------------------------------------ 定位

    private fun resolveLocation() {
        progress.visibility = View.VISIBLE
        if (AmapLocator.available) {
            AmapLocator.once(this, 15_000) { loc ->
                if (loc != null) {
                    lat = loc.latitude
                    lng = loc.longitude
                    subtitle.text = "当前位置 · 精度 ${loc.accuracy.toInt()}m"
                    loadAll()
                } else {
                    systemLastKnown()
                }
            }
        } else {
            systemLastKnown()
        }
    }

    private fun systemLastKnown() {
        val mgr = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = runCatching {
            mgr.getLastKnownLocation("fused")
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        if (loc == null) {
            progress.visibility = View.GONE
            status.text = "拿不到位置，打开定位再试"
            return
        }
        lat = loc.latitude
        lng = loc.longitude
        subtitle.text = "当前位置（缓存）"
        loadAll()
    }

    // ------------------------------------------------------------ 一次拉全

    private fun loadAll() {
        val city = prefs.cityId
        if (city.isBlank()) {
            status.text = "先在城市设置里选城市"
            return
        }
        list.removeAllViews()
        progress.visibility = View.VISIBLE

        pool.execute {
            val stations = runCatching { BusApiClient.nearStations(city, lat, lng, prefs.udid) }
                .getOrDefault(emptyList())
                .take(MAX_STATIONS)
            if (stations.isEmpty()) {
                handler.post {
                    progress.visibility = View.GONE
                    status.text = "附近没有站点"
                }
                return@execute
            }

            val data = ArrayList<Pair<NearbyStation, List<StationLine>>>()
            val lock = Object()
            val latch = java.util.concurrent.CountDownLatch(stations.size)
            for (st in stations) {
                pool.execute {
                    val lines = runCatching {
                        BusApiClient.stationLines(city, st.physicalStId, lat, lng, prefs.udid)
                    }.getOrDefault(emptyList()).take(MAX_LINES)
                    synchronized(lock) { data.add(st to lines) }
                    latch.countDown()
                }
            }
            runCatching { latch.await() }

            val ordered = synchronized(lock) { data.sortedBy { it.first.distance } }
            handler.post {
                progress.visibility = View.GONE
                status.text = "附近 ${ordered.size} 个站点 · 点线路可订阅"
                renderAll(ordered)
            }
        }
    }

    // ------------------------------------------------------------ 渲染

    private fun renderAll(data: List<Pair<NearbyStation, List<StationLine>>>) {
        list.removeAllViews()
        for ((station, lines) in data) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = 0x14000000
                setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(body)

            // 站名行
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(6))
            }
            head.addView(TextView(this).apply { text = "🚏"; textSize = 16f })
            head.addView(TextView(this).apply {
                text = station.name
                textSize = 15.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(10), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            head.addView(TextView(this).apply {
                text = if (station.distance >= 0) "${station.distance}m" else ""
                textSize = 12.5f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            body.addView(head)

            if (lines.isEmpty()) {
                body.addView(TextView(this).apply {
                    text = "暂无线路数据"
                    textSize = 12.5f
                    setPadding(dp(46), 0, dp(16), dp(12))
                    setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            }

            for (line in lines) {
                val rowView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(42), dp(8), dp(16), dp(8))
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { afterPick(line) }
                }
                val left = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                left.addView(TextView(this).apply {
                    text = buildString {
                        append(line.title)
                        if (line.routeText.isNotBlank()) append("  ").append(line.routeText)
                    }
                    textSize = 14.5f
                    setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                })
                if (line.timeRange.isNotBlank()) {
                    left.addView(TextView(this).apply {
                        text = line.timeRange
                        textSize = 11.5f
                        setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                }
                rowView.addView(left)
                rowView.addView(TextView(this).apply {
                    text = line.etaText.ifBlank { "—" }
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(
                        if (line.etaText.isNotBlank()) 0xFF1F7A3D.toInt()
                        else attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                })
                body.addView(rowView)
            }

            list.addView(card)
        }
    }

    // ------------------------------------------------------------ 订阅

    private fun afterPick(line: StationLine) {
        val places = prefs.places.filter { it.valid }
        if (places.isEmpty()) {
            toast("先添加一个地点才能订阅")
            return
        }
        val preset = placeId
        val items = places.map { it.name.ifBlank { "地点" } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("订阅 ${line.title} 到")
            .setItems(items) { _, i -> subscribe(line, places[i].id) }
            .setNegativeButton("只看不订", null)
            .show()
        if (preset.isNotBlank()) {
            // 从「绑定地点」入口进来时，默认就是当前地点，这里不用额外操作
        }
    }

    private fun subscribe(line: StationLine, targetPlaceId: String) {
        val order = if (line.order > 0) line.order else 1
        val sub = LineSub(
            cityId = prefs.cityId,
            lineId = line.lineId,
            lineName = line.lineName,
            lineNo = line.lineNo,
            direction = line.direction,
            targetOrder = order,
            stationName = line.stationName.ifBlank { line.sId },
            stationId = line.sId,
            place = targetPlaceId,
            startSn = line.startSn,
            endSn = line.endSn
        )
        val exist = prefs.lines.any {
            it.lineId == sub.lineId && it.stationId == sub.stationId && it.place == targetPlaceId
        }
        if (exist) {
            toast("已经订阅过了")
            return
        }
        prefs.lines = (prefs.lines + sub).toMutableList()
        toast("已订阅 ${sub.title} · ${sub.stationName}")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
