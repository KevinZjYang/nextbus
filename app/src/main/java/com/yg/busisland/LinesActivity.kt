package com.yg.busisland

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold
import org.json.JSONObject

/** 订阅线路：按线路 + 方向管理，不再绑定地点。 */
class LinesActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.dedupeLinesOnce()
        page = PageScaffold(
            this, "订阅线路", "按线路 + 方向置顶，不绑地点",
            showBack = true, onBack = { finish() }
        )
        setContentView(page.root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        page.clearBody()
        val lines = prefs.lines.distinctBy { it.lineId to it.direction }
        page.section("已订阅 ${lines.size} 条")
        val card = page.card()
        if (lines.isEmpty()) {
            ListRow.add(card, "🚌", "还没有订阅线路", "去附近公交或按线路号添加", chevron = false)
        }
        for (line in lines) {
            val legacyPlace = prefs.placeById(line.place)?.name
            val subtitle = buildString {
                append(line.stationName.ifBlank { "上车站未设置" })
                append(" · ")
                append(if (line.endSn.isNotBlank()) "往 ${line.endSn}" else "方向 ${line.direction}")
                if (!legacyPlace.isNullOrBlank()) {
                    append(" · 旧地点 ").append(legacyPlace)
                }
            }
            ListRow.add(card, "★", line.title, subtitle) {
                LineDetailSheet.show(this, LineDetailTarget.from(line)) { render() }
            }
        }
        page.button("＋ 添加线路") { addLine() }
        page.hint("点线路查看详情；取消订阅在详情页里完成。")
    }

    private fun addLine() {
        AlertDialog.Builder(this)
            .setTitle("添加线路")
            .setItems(arrayOf("看附近站点（当前位置）", "按线路号搜索")) { _, which ->
                if (which == 0) {
                    startActivity(
                        Intent(this, NearbyLinesActivity::class.java)
                            .putExtra(NearbyLinesActivity.EXTRA_USE_CURRENT, true)
                    )
                } else {
                    searchLine()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun searchLine() {
        val cid = prefs.cityId
        if (cid.isBlank()) {
            CityPicker.show(this, prefs) { searchLine() }
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val key = EditText(this).apply { hint = "线路关键词，如 1 或 地铁" }
        box.addView(key)
        AlertDialog.Builder(this)
            .setTitle("搜索线路（${prefs.cityName} $cid）")
            .setView(box)
            .setPositiveButton("搜索") { _, _ ->
                val k = key.text.toString().trim()
                if (k.isEmpty()) return@setPositiveButton
                toast("搜索中…")
                Thread {
                    val r = runCatching { BusApiClient.search(cid, k, prefs.udid) }
                    runOnUiThread {
                        r.onSuccess { pickLine(cid, it) }
                            .onFailure { toast("搜索失败：${it.message}") }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickLine(cityId: String, lines: List<JSONObject>) {
        if (lines.isEmpty()) {
            toast("没有结果")
            return
        }
        val labels = lines.map { o ->
            val start = o.optString("startSn")
            val end = o.optString("endSn")
            val name = o.optString("name")
            "$name  ${if (start.isBlank()) "" else "$start → $end"}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择线路")
            .setItems(labels) { _, index ->
                val o = lines[index]
                loadStations(
                    cityId = cityId,
                    lineId = o.optString("lineId"),
                    lineName = o.optString("name"),
                    lineNo = o.optString("lineNo", o.optString("name")),
                    dir = o.optInt("direction", 0)
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadStations(
        cityId: String,
        lineId: String,
        lineName: String,
        lineNo: String,
        dir: Int
    ) {
        toast("加载站点…")
        Thread {
            val r = runCatching {
                BusApiClient.routeStations(cityId, lineId, lineName, prefs.udid)
            }
            runOnUiThread {
                r.onFailure { toast("站点加载失败：${it.message}") }
                r.onSuccess { arr ->
                    if (arr.length() == 0) {
                        toast("无站点数据")
                        return@onSuccess
                    }
                    val firstSn = arr.optJSONObject(0)?.optString("sn").orEmpty()
                    val lastSn = arr.optJSONObject(arr.length() - 1)?.optString("sn").orEmpty()
                    val names = (0 until arr.length()).map { index ->
                        val s = arr.getJSONObject(index)
                        "${s.optInt("order")}. ${s.optString("sn")}"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("${lineNo.ifBlank { lineName }}  $firstSn ↔ $lastSn")
                        .setItems(names) { _, index ->
                            val s = arr.getJSONObject(index)
                            val sub = LineSub(
                                cityId = cityId,
                                lineId = lineId,
                                lineName = lineName,
                                lineNo = lineNo,
                                direction = dir,
                                targetOrder = s.optInt("order"),
                                stationName = s.optString("sn"),
                                stationId = s.optString("sId"),
                                place = "",
                                startSn = firstSn,
                                endSn = lastSn
                            )
                            if (prefs.addLine(sub)) {
                                render()
                                toast("已订阅：${sub.title} @ ${sub.stationName}")
                            } else {
                                toast("已订阅，无需重复添加")
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
