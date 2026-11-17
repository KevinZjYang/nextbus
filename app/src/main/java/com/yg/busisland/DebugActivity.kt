package com.yg.busisland

import android.os.Bundle
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold

/** 仅 debug 构建可见：最近一次请求、接口自检。 */
class DebugActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(
            this, "开发者", "仅 debug 构建可见",
            showBack = true, onBack = { finish() }
        )
        render()
        if (intent?.getBooleanExtra("selftest", false) == true) selfTest()
        setContentView(page.root)
    }

    private fun render() {
        page.clearBody()

        page.section("最近一次查询")
        val logCard = page.card()
        ListRow.add(logCard, "🏙", "城市", CityPicker.label(prefs), chevron = false)
        ListRow.add(logCard, "📝", "状态", prefs.lastStatus.ifBlank { "—" }, chevron = false)
        ListRow.add(logCard, "🔗", "URL", prefs.lastQueryUrl.ifBlank { "—" }, chevron = false)
        ListRow.add(logCard, "📦", "响应", prefs.lastQueryBody.take(160).ifBlank { "—" }, chevron = false)
        page.button("跑一遍自检（结果看 logcat）", primary = false) { selfTest() }

        page.section("说明")
        page.hint("自检会依次请求搜索、站点和实时接口；日志 tag: BusIslandSelfTest。")
    }

    /** 打一遍接口，结果写 logcat：BusIslandSelfTest */
    private fun selfTest() {
        val log = StringBuilder()
        Thread {
            runCatching {
                val cid = prefs.cityId.ifBlank { "027" }
                log.append("city=").append(cid).append('\n')
                val lines = BusApiClient.search(cid, "1", prefs.udid)
                log.append("search=").append(lines.size).append('\n')
                val lineId = lines.firstOrNull()?.optString("lineId") ?: "010-1-0"
                val name = lines.firstOrNull()?.optString("name") ?: "1"
                val st = BusApiClient.routeStations(cid, lineId, name, prefs.udid)
                log.append("stations=").append(st.length()).append('\n')
                if (st.length() > 0) {
                    val s = st.optJSONObject(0)
                    val sub = LineSub(
                        cid, lineId, name, name, 0,
                        s.optInt("order"), s.optString("sn"), s.optString("sId"), ""
                    )
                    val r = BusApiClient.realtime(sub, prefs.udid)
                    log.append("realtime ok=").append(r.ok).append(" buses=").append(r.buses.size)
                }
            }.onFailure { log.append("ERR ").append(it.message) }
            android.util.Log.i("BusIslandSelfTest", log.toString())
        }.start()
        toast("自检已启动，看 logcat: BusIslandSelfTest")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
