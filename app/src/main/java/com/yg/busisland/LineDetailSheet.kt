package com.yg.busisland

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.yg.busisland.ui.colorOnSurface
import com.yg.busisland.ui.colorOnSurfaceVariant
import com.yg.busisland.ui.colorPrimary
import com.yg.busisland.ui.colorSurface
import com.yg.busisland.ui.colorSurfaceVariant
import com.yg.busisland.ui.dp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 线路详情：点一行先看详情，订阅/取消订阅只在这里发生。
 *
 * 不依赖地点绑定；订阅仍然只记「线路 + 方向 + 上车站」。
 */
data class LineDetailTarget(
    val cityId: String,
    val lineId: String,
    val lineName: String,
    val lineNo: String,
    val direction: Int,
    val startSn: String,
    val endSn: String,
    val stationName: String,
    val stationId: String,
    val order: Int,
    val distance: Int?,
    val etaText: String,
    val timeRange: String = "",
    val hasNearby: Boolean = false
) {
    val title: String get() = when {
        lineNo.isBlank() -> lineName
        lineNo.endsWith("路") || lineNo.endsWith("线") -> lineNo
        else -> "$lineNo 路"
    }

    val towards: String get() = if (endSn.isNotBlank()) "往 $endSn" else ""

    val routeText: String get() = when {
        startSn.isBlank() && endSn.isBlank() -> ""
        startSn.isBlank() -> endSn
        endSn.isBlank() -> startSn
        else -> "$startSn ↔ $endSn"
    }

    companion object {
        fun from(row: NearbyLineRow, cityId: String): LineDetailTarget {
            val line = row.line
            val station = row.station
            val sub = row.subscription
            return LineDetailTarget(
                cityId = cityId,
                lineId = line.lineId,
                lineName = line.lineName,
                lineNo = line.lineNo,
                direction = line.direction,
                startSn = line.startSn,
                endSn = line.endSn,
                stationName = sub?.stationName?.takeIf { it.isNotBlank() }
                    ?: line.stationName.ifBlank { station.name },
                stationId = sub?.stationId?.takeIf { it.isNotBlank() }
                    ?: line.sId.ifBlank { station.sId },
                order = sub?.targetOrder ?: line.order,
                distance = row.boardingDistanceM.takeIf { it >= 0 },
                etaText = if (row.boardingMatchesStation) line.etaText else "",
                timeRange = line.timeRange,
                hasNearby = true
            )
        }

        fun from(sub: LineSub): LineDetailTarget = LineDetailTarget(
            cityId = sub.cityId,
            lineId = sub.lineId,
            lineName = sub.lineName,
            lineNo = sub.lineNo,
            direction = sub.direction,
            startSn = sub.startSn,
            endSn = sub.endSn,
            stationName = sub.stationName,
            stationId = sub.stationId,
            order = sub.targetOrder,
            distance = null,
            etaText = "",
            timeRange = "",
            hasNearby = false
        )
    }
}

object LineDetailSheet {

    fun show(activity: Activity, target: LineDetailTarget, onChanged: () -> Unit) {
        val prefs = Prefs(activity)
        val dialog = BottomSheetDialog(activity)
        val executor = Executors.newSingleThreadExecutor()
        val closed = AtomicBoolean(false)
        var requestId = 0

        // 当前选中的上车站；打开时默认取订阅记录，没有订阅则取附近这一站。
        var pickedOrder = target.order
        var pickedName = target.stationName
        var pickedId = target.stationId
        var pickedDistance: Int? = target.distance
        var pickedEta = target.etaText
        var loadedStops: List<RouteStop> = emptyList()
        var boardingHint = ""
        var etaResult: RealTimeResult? = null
        var etaLoading = false
        var etaSeq = 0
        val etaHandler = Handler(Looper.getMainLooper())
        val etaExecutor = Executors.newSingleThreadExecutor()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(16))
            setBackgroundColor(activity.colorSurface())
        }

        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(activity).apply {
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.colorOnSurface())
        }
        val directionView = TextView(activity).apply {
            textSize = 13f
            setTextColor(activity.colorOnSurfaceVariant())
            setPadding(0, activity.dp(3), 0, 0)
        }
        titleBox.addView(titleView)
        titleBox.addView(directionView)
        head.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(activity).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(activity.colorPrimary())
            setPadding(activity.dp(12), activity.dp(8), 0, activity.dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(close)
        root.addView(head)

        val summary = MaterialCardView(activity).apply {
            radius = activity.dp(16).toFloat()
            strokeWidth = activity.dp(1)
            strokeColor = activity.colorSurfaceVariant()
            setCardBackgroundColor(activity.colorSurfaceVariant())
        }
        val summaryBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        }
        val stationView = TextView(activity).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.colorOnSurface())
        }
        val etaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(6), 0, 0)
        }
        val etaBigView = TextView(activity).apply {
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.colorPrimary())
            maxLines = 1
        }
        val etaSubView = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(activity.colorOnSurfaceVariant())
            gravity = Gravity.END
            maxLines = 2
            setPadding(activity.dp(10), 0, 0, 0)
        }
        etaRow.addView(etaBigView, LinearLayout.LayoutParams(-2, -2))
        etaRow.addView(etaSubView, LinearLayout.LayoutParams(0, -2, 1f))
        val metaView = TextView(activity).apply {
            textSize = 13f
            setTextColor(activity.colorOnSurfaceVariant())
            setPadding(0, activity.dp(4), 0, 0)
        }
        val routeView = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(activity.colorOnSurfaceVariant())
            setPadding(0, activity.dp(3), 0, 0)
        }
        summaryBody.addView(stationView)
        summaryBody.addView(etaRow)
        summaryBody.addView(metaView)
        summaryBody.addView(routeView)
        summary.addView(summaryBody)
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })

        val routeHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(16), 0, activity.dp(6))
        }
        routeHead.addView(TextView(activity).apply {
            text = "途经站点"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.colorOnSurface())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val routeCount = TextView(activity).apply {
            textSize = 12f
            setTextColor(activity.colorOnSurfaceVariant())
        }
        routeHead.addView(routeCount)
        root.addView(routeHead)

        val routeScroll = NestedScrollView(activity).apply { isFillViewport = true }
        val routeList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        routeScroll.addView(routeList)
        root.addView(
            routeScroll,
            LinearLayout.LayoutParams(-1, activity.dp(286))
        )

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, activity.dp(12), 0, 0)
        }
        val mapButton = MaterialButton(activity).apply {
            text = "在地图上查看"
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            height = activity.dp(46)
            visibility = if (target.hasNearby && BuildConfig.AMAP_KEY.isNotBlank()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            setOnClickListener {
                dialog.dismiss()
                activity.startActivity(
                    android.content.Intent(activity, NearbyMapActivity::class.java)
                        .putExtra(NearbyMapActivity.EXTRA_LINE_ID, target.lineId)
                        .putExtra(NearbyMapActivity.EXTRA_DIRECTION, target.direction)
                        .putExtra(NearbyMapActivity.EXTRA_TARGET_ORDER, pickedOrder)
                )
            }
        }
        val subButton = MaterialButton(activity).apply {
            isAllCaps = false
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            height = activity.dp(46)
        }
        actions.addView(mapButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = activity.dp(8)
        })
        actions.addView(subButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)

        fun toast(text: String) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }

        fun currentStop(stop: RouteStop): Boolean {
            if (pickedId.isNotBlank() && stop.sId == pickedId) return true
            if (pickedName.isNotBlank() && stop.name == pickedName) return true
            return pickedOrder > 0 && stop.order == pickedOrder
        }

        var loadRoute: () -> Unit = {}
        var loadRealtime: () -> Unit = {}
        var syncSubscription: (Boolean) -> Unit = {}
        var refreshSummary: () -> Unit = {}

        fun renderRoute(stops: List<RouteStop>, error: String? = null, scrollTo: Int = -1) {
            routeList.removeAllViews()
            routeCount.text = when {
                stops.isNotEmpty() -> "共 ${stops.size} 站 · 点击选上车站"
                error != null -> "加载失败"
                else -> "暂无站点"
            }
            if (error != null) {
                routeList.addView(TextView(activity).apply {
                    text = error
                    textSize = 13f
                    setTextColor(activity.colorOnSurfaceVariant())
                    setPadding(0, activity.dp(8), 0, activity.dp(8))
                })
                routeList.addView(MaterialButton(activity).apply {
                    text = "重试"
                    isAllCaps = false
                    textSize = 13f
                    minHeight = 0
                    minimumHeight = 0
                    height = activity.dp(40)
                    setOnClickListener { loadRoute() }
                })
                return
            }
            if (stops.isEmpty()) {
                routeList.addView(TextView(activity).apply {
                    text = "没有拿到站点数据"
                    textSize = 13f
                    setTextColor(activity.colorOnSurfaceVariant())
                    setPadding(0, activity.dp(8), 0, activity.dp(8))
                })
                return
            }
            for (stop in stops) {
                val current = currentStop(stop)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                    if (current) setBackgroundColor(0x140066FF)
                    isClickable = true
                    setOnClickListener {
                        val y = routeScroll.scrollY
                        pickedOrder = stop.order
                        pickedId = stop.sId
                        pickedName = stop.name
                        pickedDistance = null
                        pickedEta = ""
                        boardingHint = if (prefs.isSubscribed(target.lineId, target.direction)) {
                            ""
                        } else {
                            "已选择，点订阅保存"
                        }
                        etaSeq++
                        etaLoading = false
                        etaResult = null
                        refreshSummary()
                        renderRoute(loadedStops, scrollTo = y)
                        loadRealtime()
                        syncSubscription(true)
                    }
                }
                row.addView(TextView(activity).apply {
                    text = stop.order.toString()
                    textSize = 12f
                    setTextColor(activity.colorOnSurfaceVariant())
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(activity.dp(30), -2))
                row.addView(TextView(activity).apply {
                    text = stop.name
                    textSize = 14f
                    setTextColor(if (current) activity.colorPrimary() else activity.colorOnSurface())
                    setTypeface(typeface, if (current) Typeface.BOLD else Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (current) {
                    row.addView(TextView(activity).apply {
                        text = "上车站"
                        textSize = 11f
                        setTextColor(activity.colorPrimary())
                        setPadding(activity.dp(6), activity.dp(2), activity.dp(6), activity.dp(2))
                        setBackgroundColor(0x1F0066FF)
                    })
                }
                routeList.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            if (scrollTo >= 0) {
                routeScroll.post { routeScroll.scrollTo(0, scrollTo) }
            }
        }

        loadRoute = {
            val id = ++requestId
            routeList.removeAllViews()
            routeCount.text = "加载中…"
            val loading = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, activity.dp(12), 0, activity.dp(12))
            }
            loading.addView(ProgressBar(activity).apply {
                indeterminateTintList = ColorStateList.valueOf(activity.colorPrimary())
            }, LinearLayout.LayoutParams(activity.dp(22), activity.dp(22)))
            loading.addView(TextView(activity).apply {
                text = "加载站点…"
                textSize = 13f
                setTextColor(activity.colorOnSurfaceVariant())
                setPadding(activity.dp(10), 0, 0, 0)
            })
            routeList.addView(loading)

            executor.execute {
                val result = runCatching {
                    val city = target.cityId.ifBlank { prefs.cityId }
                    if (city.isBlank()) error("先选择城市")
                    BusApiClient.lineRoute(city, target.lineId, target.lineName, prefs.udid)
                }
                activity.runOnUiThread {
                    if (closed.get() || id != requestId || activity.isFinishing) return@runOnUiThread
                    result.onSuccess { route ->
                        loadedStops = route.stops
                        val mismatch = route.stops.isNotEmpty() && route.stops.none { stop ->
                            (pickedId.isNotBlank() && stop.sId == pickedId) ||
                                (pickedName.isNotBlank() && stop.name == pickedName) ||
                                (pickedOrder > 0 && stop.order == pickedOrder)
                        }
                        if (mismatch) {
                            val found = route.stops.firstOrNull {
                                pickedId.isNotBlank() && it.sId == pickedId
                            } ?: route.stops.firstOrNull {
                                pickedName.isNotBlank() && it.name == pickedName
                            }
                            if (found != null) {
                                pickedOrder = found.order
                                pickedId = found.sId
                                pickedName = found.name
                                pickedDistance = null
                                pickedEta = ""
                                boardingHint = ""
                                if (prefs.isSubscribed(target.lineId, target.direction)) {
                                    syncSubscription(true)
                                }
                            } else if (pickedName.isNotBlank() || pickedOrder > 0) {
                                pickedOrder = 0
                                boardingHint = "原上车站不在该线路，请重新选择"
                            }
                        }
                        refreshSummary()
                        renderRoute(route.stops)
                        loadRealtime()
                    }
                    result.onFailure {
                        loadedStops = emptyList()
                        renderRoute(emptyList(), "站点加载失败：${it.message ?: "网络错误"}")
                    }
                }
            }
        }

        fun renderHeader() {
            val subscribed = prefs.isSubscribed(target.lineId, target.direction)
            titleView.text = if (subscribed) "★ ${target.title}" else target.title
            directionView.text = target.towards
        }

        fun metersText(meters: Int): String =
            if (meters < 1000) "${meters}m"
            else String.format(java.util.Locale.getDefault(), "%.1fkm", meters / 1000.0)

        fun renderSummary() {
            stationView.text = pickedName.ifBlank { "上车站未设置" }
            val result = etaResult
            val bus = result?.firstBus()
            etaBigView.text = when {
                pickedOrder <= 0 -> "—"
                etaLoading && result == null -> "…"
                result != null && !result.ok -> "失败"
                bus != null -> bus.etaText
                result != null && (result.lineDesc.contains("不在运营") ||
                    result.lineDesc.contains("停运")) -> "停运"
                result != null -> "暂无车"
                else -> "…"
            }
            etaSubView.text = when {
                pickedOrder <= 0 -> "先点下方站点选择上车站"
                bus != null -> buildString {
                    if (bus.clock.isNotBlank()) append("预计 ").append(bus.clock)
                    result.buses.getOrNull(1)?.let {
                        if (isNotEmpty()) append(" · ")
                        append("后一班 ").append(it.etaText)
                    }
                    if (bus.distance > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("距站 ").append(metersText(bus.distance))
                    }
                }
                result != null && !result.ok -> result.message.ifBlank { "实时查询失败" }
                result != null && result.lineDesc.isNotBlank() -> result.lineDesc
                !result?.nextOperationDesc.isNullOrBlank() -> result.nextOperationDesc
                etaLoading -> "正在查实时到站…"
                else -> target.timeRange
            }
            val parts = mutableListOf<String>()
            val distance = pickedDistance
            if (distance != null && distance >= 0) parts.add("${distance}m")
            if (pickedEta.isNotBlank()) parts.add(pickedEta)
            if (!target.hasNearby) parts.add("附近未发现")
            if (boardingHint.isNotBlank()) parts.add(boardingHint)
            metaView.text = if (parts.isEmpty()) "点站点可改上车站" else parts.joinToString(" · ")
            routeView.text = listOf(target.timeRange, target.routeText)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }

        loadRealtime = {
            val city = target.cityId.ifBlank { prefs.cityId }
            val order = pickedOrder
            if (closed.get() || city.isBlank() || order <= 0) {
                etaLoading = false
                refreshSummary()
            } else if (!etaLoading) {
                etaLoading = true
                val id = ++etaSeq
                val sub = LineSub(
                    cityId = city,
                    lineId = target.lineId,
                    lineName = target.lineName,
                    lineNo = target.lineNo,
                    direction = target.direction,
                    targetOrder = order,
                    stationName = pickedName.ifBlank { "上车站" },
                    stationId = pickedId,
                    place = "",
                    startSn = target.startSn,
                    endSn = target.endSn
                )
                refreshSummary()
                etaExecutor.execute {
                    val result = runCatching { BusApiClient.realtime(sub, prefs.udid) }
                    activity.runOnUiThread {
                        if (closed.get() || id != etaSeq) return@runOnUiThread
                        etaLoading = false
                        result.onSuccess { etaResult = it }
                            .onFailure {
                                etaResult = RealTimeResult(
                                    lineDesc = "",
                                    preArrival = "",
                                    buses = emptyList(),
                                    ok = false,
                                    message = it.message ?: "实时查询失败"
                                )
                            }
                        refreshSummary()
                    }
                }
            }
        }

        refreshSummary = { renderSummary() }

        fun renderSubscribe() {
            val subscribed = prefs.isSubscribed(target.lineId, target.direction)
            subButton.text = if (subscribed) "取消订阅" else "订阅线路"
            if (subscribed) {
                subButton.backgroundTintList = ColorStateList.valueOf(activity.colorSurfaceVariant())
                subButton.setTextColor(activity.colorOnSurfaceVariant())
            } else {
                subButton.backgroundTintList = ColorStateList.valueOf(activity.colorPrimary())
                subButton.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        syncSubscription = { silent ->
            if (prefs.isSubscribed(target.lineId, target.direction)) {
                val city = target.cityId.ifBlank { prefs.cityId }
                val existing = prefs.lines.firstOrNull {
                    it.lineId == target.lineId && it.direction == target.direction
                }
                if (existing != null && pickedOrder > 0 && city.isNotBlank()) {
                    prefs.upsertLine(
                        existing.copy(
                            cityId = city,
                            targetOrder = pickedOrder,
                            stationName = pickedName.ifBlank { "上车站" },
                            stationId = pickedId,
                            startSn = target.startSn,
                            endSn = target.endSn
                        )
                    )
                    renderHeader()
                    renderSubscribe()
                    onChanged()
                    if (!silent) toast("已更新上车站：${pickedName}")
                }
            }
        }

        subButton.setOnClickListener {
            val city = target.cityId.ifBlank { prefs.cityId }
            if (city.isBlank()) {
                toast("先选择城市")
                return@setOnClickListener
            }
            if (prefs.isSubscribed(target.lineId, target.direction)) {
                AlertDialog.Builder(activity)
                    .setTitle("${target.title} · ${pickedName.ifBlank { "上车站" }}")
                    .setMessage("取消订阅这条线路？")
                    .setPositiveButton("取消订阅") { _, _ ->
                        prefs.removeLine(target.lineId, target.direction)
                        renderHeader()
                        renderSubscribe()
                        onChanged()
                        toast("已取消订阅")
                    }
                    .setNegativeButton("保留", null)
                    .show()
                return@setOnClickListener
            }
            if (loadedStops.isEmpty()) {
                toast("请先等站点加载完成")
                return@setOnClickListener
            }
            val stop = loadedStops.firstOrNull {
                (pickedId.isNotBlank() && it.sId == pickedId) ||
                    (pickedOrder > 0 && it.order == pickedOrder)
            }
            if (pickedOrder <= 0 || stop == null) {
                toast("请先在途经站点中选择上车站")
                return@setOnClickListener
            }
            pickedOrder = stop.order
            pickedId = stop.sId
            pickedName = stop.name
            boardingHint = ""
            val sub = LineSub(
                cityId = city,
                lineId = target.lineId,
                lineName = target.lineName,
                lineNo = target.lineNo,
                direction = target.direction,
                targetOrder = pickedOrder,
                stationName = pickedName.ifBlank { "上车站" },
                stationId = pickedId,
                place = "",
                startSn = target.startSn,
                endSn = target.endSn
            )
            if (prefs.upsertLine(sub)) {
                toast("已订阅，已置顶")
            } else {
                toast("已更新上车站：${pickedName}")
            }
            onChanged()
            renderHeader()
            renderSubscribe()
            refreshSummary()
        }

        renderHeader()
        renderSummary()
        renderSubscribe()
        loadRoute()

        val etaTick = object : Runnable {
            override fun run() {
                loadRealtime()
                etaHandler.postDelayed(this, 15_000L)
            }
        }
        etaHandler.postDelayed(etaTick, 400L)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            closed.set(true)
            etaHandler.removeCallbacksAndMessages(null)
            etaExecutor.shutdownNow()
            executor.shutdownNow()
        }
        dialog.show()
    }
}
