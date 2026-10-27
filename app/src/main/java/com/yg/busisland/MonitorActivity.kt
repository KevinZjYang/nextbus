package com.yg.busisland

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold
import com.yg.busisland.ui.TimeWheelDialog
import com.yg.busisland.ui.WeekdayPicker

/** 通勤监控：开关、城市、早晚高峰时段、工作日 */
class MonitorActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold
    private lateinit var tvCity: TextView
    private lateinit var tvMorningStart: TextView
    private lateinit var tvMorningEnd: TextView
    private lateinit var tvEveningStart: TextView
    private lateinit var tvEveningEnd: TextView
    private var cityCache: List<CityInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(
            this, "通勤监控", "只在时段内定位，离家远会降频",
            showBack = true, onBack = { finish() }
        )

        page.section("状态")
        val statusCard = page.card()
        ListRow.switch(statusCard, "🚌", "启用通勤监控", "关闭后不再定位，也不上岛", prefs.enabled) { on ->
            prefs.enabled = on
            if (on) {
                MonitorService.start(this)
                Scheduler.armNext(this)
            } else {
                MonitorService.stop(this)
                Scheduler.cancel(this)
                HyperIsland.cancel(this)
            }
        }
        tvCity = ListRow.add(statusCard, "🏙", "城市", cityLabel()) { pickCity() }

        page.section("通勤时段")
        val timeCard = page.card()
        val s = prefs.schedule
        tvMorningStart = ListRow.add(timeCard, "🌅", "早高峰开始", fmt(s.morningStart)) { pickTime(0) }
        tvMorningEnd = ListRow.add(timeCard, "🌇", "早高峰结束", fmt(s.morningEnd)) { pickTime(1) }
        tvEveningStart = ListRow.add(timeCard, "🌆", "晚高峰开始", fmt(s.eveningStart)) { pickTime(2) }
        tvEveningEnd = ListRow.add(timeCard, "🌃", "晚高峰结束", fmt(s.eveningEnd)) { pickTime(3) }
        page.hint("只有这两段时间内才会定位查车；其余时间服务休眠，由闹钟唤醒。")

        page.section("工作日")
        val weekCard = page.card()
        WeekdayPicker.attach(weekCard, s.weekdays) { mask ->
            prefs.schedule = prefs.schedule.copy(weekdays = mask)
            Scheduler.armNext(this)
        }
        page.hint("非通勤日不启动监控。")

        setContentView(page.root)
    }

    private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun pickTime(which: Int) {
        val s = prefs.schedule
        val (title, cur) = when (which) {
            0 -> "早高峰开始" to s.morningStart
            1 -> "早高峰结束" to s.morningEnd
            2 -> "晚高峰开始" to s.eveningStart
            else -> "晚高峰结束" to s.eveningEnd
        }
        TimeWheelDialog.show(this, title, cur) { m ->
            val now = prefs.schedule
            prefs.schedule = when (which) {
                0 -> now.copy(morningStart = m)
                1 -> now.copy(morningEnd = m)
                2 -> now.copy(eveningStart = m)
                else -> now.copy(eveningEnd = m)
            }
            Scheduler.armNext(this)
            refreshTimes()
        }
    }

    private fun refreshTimes() {
        val s = prefs.schedule
        tvMorningStart.text = fmt(s.morningStart)
        tvMorningEnd.text = fmt(s.morningEnd)
        tvEveningStart.text = fmt(s.eveningStart)
        tvEveningEnd.text = fmt(s.eveningEnd)
    }

    // ------------------------------------------------------------ 城市

    private fun cityLabel(): String =
        if (prefs.cityId.isBlank()) "未设置（点我选择）" else "${prefs.cityName} ${prefs.cityId}"

    private fun pickCity() {
        val items = arrayOf("自动检测（用已设地点）", "搜索城市名", "用当前位置定位")
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
            toast("还没有设点的地点，先添加家或公司")
            return
        }
        resolveCity(p.lat, p.lng)
    }

    private fun autoCityFromLocation() {
        val mgr = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val loc = runCatching {
            mgr.getLastKnownLocation("fused")
                ?: mgr.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: mgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
        }.getOrNull()
        if (loc == null) {
            toast("暂无定位缓存，改用搜索城市名")
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
                    toast("识别失败，请手动搜索")
                    return@runOnUiThread
                }
                prefs.cityId = c.id
                prefs.cityName = c.name
                prefs.cityLat = c.lat
                prefs.cityLng = c.lng
                tvCity.text = cityLabel()
                toast("城市：${c.name}（${c.id}）")
            }
        }.start()
    }

    private fun searchCity() {
        val box = EditText(this).apply { hint = "城市名，如 北京 / 上海" }
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
                                tvCity.text = cityLabel()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
