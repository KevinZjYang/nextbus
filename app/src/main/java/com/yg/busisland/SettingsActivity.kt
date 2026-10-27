package com.yg.busisland

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold

/** 设置：围栏与刷新、后台运行、关于、开发者 */
class SettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold
    private lateinit var tvFence: TextView
    private lateinit var tvInterval: TextView
    private lateinit var tvBattery: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(this, "设置", null, showBack = true, onBack = { finish() })
        render()
        setContentView(page.root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        page.clearBody()

        page.section("围栏与刷新")
        val fenceCard = page.card()
        tvFence = ListRow.add(fenceCard, "📍", "围栏半径", "${prefs.radius} m") { pickFence() }
        tvInterval = ListRow.add(fenceCard, "🔄", "刷新间隔", "${prefs.intervalSec} s") { pickInterval() }
        page.hint("离最近地点超过围栏半径就降低刷新频率，省电。")

        page.section("后台运行")
        val keepCard = page.card()
        ListRow.add(keepCard, "🚀", "自启动", null) { openAutoStart() }
        tvBattery = ListRow.add(keepCard, "🔋", "电池优化", if (batteryIgnored()) "已忽略" else "去设置") {
            requestIgnoreBattery()
        }
        ListRow.add(keepCard, "⚡", "省电策略", null) { openPowerSaver() }
        ListRow.footnote(keepCard, "三项都设好，后台才不会被系统清掉。")

        page.section("其他")
        val moreCard = page.card()
        ListRow.add(moreCard, "ℹ️", "关于") {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        if (BuildConfig.DEBUG) {
            ListRow.add(moreCard, "🧪", "开发者") {
                startActivity(Intent(this, DebugActivity::class.java))
            }
        }
    }

    private fun pickFence() {
        val values = intArrayOf(200, 300, 500, 800, 1000, 1500, 2000)
        AlertDialog.Builder(this)
            .setTitle("围栏半径")
            .setItems(values.map { "$it m" }.toTypedArray()) { _, i ->
                prefs.radius = values[i]
                tvFence.text = "${prefs.radius} m"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickInterval() {
        val values = intArrayOf(20, 30, 45, 60, 90, 120)
        AlertDialog.Builder(this)
            .setTitle("刷新间隔")
            .setItems(values.map { "$it 秒" }.toTypedArray()) { _, i ->
                prefs.intervalSec = values[i]
                tvInterval.text = "${prefs.intervalSec} s"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 系统页跳转

    private fun batteryIgnored(): Boolean = runCatching {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    private fun openAutoStart() {
        val intents = listOf(
            Intent().setComponent(android.content.ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        toast("没找到自启动设置页，去安全中心手动开")
    }

    private fun requestIgnoreBattery() {
        if (batteryIgnored()) {
            toast("已经在忽略名单里了")
            return
        }
        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun openPowerSaver() {
        val intents = listOf(
            Intent().setComponent(android.content.ComponentName(
                "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            )).putExtra("package_name", packageName).putExtra("package_label", "下一班"),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        toast("没找到省电策略页，去省电与电池里手动设")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
