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

/** 订阅线路：每条线路绑定到一个地点 + 一个上车点 */
class LinesActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(this, "订阅线路", "线路绑定地点，上车点决定到站时间", showBack = true, onBack = { finish() })
        render()
        setContentView(page.root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        page.clearBody()
        val lines = prefs.lines
        page.section("已订阅 ${lines.size} 条")
        val card = page.card()
        if (lines.isEmpty()) {
            ListRow.add(card, "🚌", "还没有订阅线路", "点下面添加", chevron = false)
        }
        for (l in lines) {
            val placeName = prefs.placeById(l.place)?.name ?: "未绑定"
            val sub = buildString {
                append(l.stationName)
                if (l.routeText.isNotBlank()) append(" · ").append(l.routeText)
                append(" · ").append(placeName)
            }
            ListRow.add(card, "🚌", l.title, sub) { remove(l) }
        }
        page.button("＋ 添加线路") { addLine() }
        page.hint("点击线路可删除；新增时优先用「附近站点」，会带上实时到站时间。")
    }

    private fun remove(l: LineSub) {
        AlertDialog.Builder(this)
            .setTitle("${l.title} · ${l.stationName}")
            .setMessage("删除这条订阅？")
            .setPositiveButton("删除") { _, _ ->
                prefs.lines = prefs.lines.filterNot {
                    it.lineId == l.lineId && it.stationId == l.stationId && it.place == l.place
                }.toMutableList()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addLine() {
        val places = prefs.places.filter { it.valid }
        if (places.isEmpty()) {
            toast("先添加地点并选点")
            startActivity(Intent(this, PlacesActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("线路绑定到")
            .setItems(places.map { it.name.ifBlank { "地点" } }.toTypedArray()) { _, i ->
                val place = places[i]
                AlertDialog.Builder(this)
                    .setTitle("「${place.name.ifBlank { "地点" }}」添加线路")
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
            startActivity(Intent(this, MonitorActivity::class.java))
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
                        r.onSuccess { pickLine(cid, place, it) }
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
                        .setTitle("${lineNo.ifBlank { lineName }}  $firstSn ↔ $lastSn")
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
