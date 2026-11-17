package com.yg.busisland

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
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
            return LineDetailTarget(
                cityId = cityId,
                lineId = line.lineId,
                lineName = line.lineName,
                lineNo = line.lineNo,
                direction = line.direction,
                startSn = line.startSn,
                endSn = line.endSn,
                stationName = line.stationName.ifBlank { station.name },
                stationId = line.sId.ifBlank { station.sId },
                order = line.order,
                distance = station.distance,
                etaText = line.etaText,
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
            if (target.stationId.isNotBlank() && stop.sId == target.stationId) return true
            if (target.stationName.isNotBlank() && stop.name == target.stationName) return true
            return target.order > 0 && stop.order == target.order
        }

        var loadRoute: () -> Unit = {}

        fun renderRoute(stops: List<RouteStop>, error: String? = null) {
            routeList.removeAllViews()
            routeCount.text = when {
                stops.isNotEmpty() -> "共 ${stops.size} 站"
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
                    result.onSuccess { renderRoute(it.stops) }
                    result.onFailure { renderRoute(emptyList(), "站点加载失败：${it.message ?: "网络错误"}") }
                }
            }
        }

        fun renderHeader() {
            val subscribed = prefs.isSubscribed(target.lineId, target.direction)
            titleView.text = if (subscribed) "★ ${target.title}" else target.title
            directionView.text = target.towards
        }

        fun renderSummary() {
            stationView.text = target.stationName.ifBlank { "上车站未设置" }
            val parts = mutableListOf<String>()
            if (target.distance != null && target.distance >= 0) parts.add("${target.distance}m")
            if (target.etaText.isNotBlank()) parts.add(target.etaText)
            if (!target.hasNearby) parts.add("附近未发现")
            metaView.text = if (parts.isEmpty()) "暂无实时到站" else parts.joinToString(" · ")
            routeView.text = listOf(target.timeRange, target.routeText)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }

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

        subButton.setOnClickListener {
            val city = target.cityId.ifBlank { prefs.cityId }
            if (city.isBlank()) {
                toast("先选择城市")
                return@setOnClickListener
            }
            if (prefs.isSubscribed(target.lineId, target.direction)) {
                AlertDialog.Builder(activity)
                    .setTitle("${target.title} · ${target.stationName}")
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
            val sub = LineSub(
                cityId = city,
                lineId = target.lineId,
                lineName = target.lineName,
                lineNo = target.lineNo,
                direction = target.direction,
                targetOrder = target.order.coerceAtLeast(1),
                stationName = target.stationName.ifBlank { "上车站" },
                stationId = target.stationId,
                place = "",
                startSn = target.startSn,
                endSn = target.endSn
            )
            if (prefs.addLine(sub)) {
                toast("已订阅，已置顶")
                onChanged()
            } else {
                toast("已订阅")
            }
            renderHeader()
            renderSubscribe()
        }

        renderHeader()
        renderSummary()
        renderSubscribe()
        loadRoute()

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            closed.set(true)
            executor.shutdownNow()
        }
        dialog.show()
    }
}
