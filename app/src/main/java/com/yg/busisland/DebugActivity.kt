package com.yg.busisland

import android.os.Bundle
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold

/** 仅 debug 构建可见：岛上诊断、测试推送、最近一次请求 */
class DebugActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(this, "开发者", "仅 debug 构建可见", showBack = true, onBack = { finish() })
        render()

        when {
            intent?.getBooleanExtra("islandtest", false) == true -> sendTestIsland()
            intent?.getBooleanExtra("selftest", false) == true -> selfTest()
        }
        setContentView(page.root)
    }

    private fun render() {
        page.clearBody()

        page.section("超级岛")
        val islandCard = page.card()
        ListRow.add(islandCard, "📱", "系统支持", HyperIsland.isSupported().toString(), chevron = false)
        ListRow.add(islandCard, "🔢", "协议版本", HyperIsland.protocolVersion(this).toString(), chevron = false)
        ListRow.add(islandCard, "🔑", "焦点权限", HyperIsland.hasFocusPermission(this).toString(), chevron = false)
        ListRow.add(islandCard, "🧪", "调试模式", if (prefs.debugMode) "开" else "关") {
            prefs.debugMode = !prefs.debugMode
            render()
        }
        page.button("发送测试岛") { sendTestIsland() }
        page.button("打开焦点通知设置", primary = false) { HyperIsland.openFocusSettings(this) }

        page.section("最近一次查询")
        val logCard = page.card()
        ListRow.add(logCard, "📝", "状态", prefs.lastStatus.ifBlank { "—" }, chevron = false)
        ListRow.add(logCard, "🔗", "URL", prefs.lastQueryUrl.ifBlank { "—" }, chevron = false)
        ListRow.add(logCard, "📦", "响应", prefs.lastQueryBody.take(160).ifBlank { "—" }, chevron = false)
        page.button("跑一遍自检（结果看 logcat）", primary = false) { selfTest() }
    }

    private fun sendTestIsland() {
        if (prefs.islandSound) Sfx.playNotification(this, Notify.currentSound(this, prefs.remindSound))
        HyperIsland.showMedia(this, "1 路", "3 分钟", "往 新首钢 · 下一班 8 分钟")
        toast("已发送")
    }

    /** 打一遍接口，结果写 logcat：BusIslandSelfTest */
    private fun selfTest() {
        val log = StringBuilder()
        Thread {
            runCatching {
                log.append("city=").append(prefs.cityId).append('\n')
                val lines = BusApiClient.search("027", "1", prefs.udid)
                log.append("search=").append(lines.size).append('\n')
                val lineId = lines.firstOrNull()?.optString("lineId") ?: "010-1-0"
                val name = lines.firstOrNull()?.optString("name") ?: "1"
                val st = BusApiClient.routeStations("027", lineId, name, prefs.udid)
                log.append("stations=").append(st.length()).append('\n')
                if (st.length() > 0) {
                    val s = st.optJSONObject(0)
                    val sub = LineSub("027", lineId, name, name, 0, s.optInt("order"), s.optString("sn"), s.optString("sId"), "home")
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
