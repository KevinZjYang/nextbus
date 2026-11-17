package com.yg.busisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold

/** 设置：只剩定位、城市和关于。 */
class SettingsActivity : com.yg.busisland.ui.PageActivity() {

    companion object {
        private const val REQ_LOCATION = 301
    }

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(
            this, "设置", "打开即查，不跑后台服务",
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

        page.section("定位与城市")
        val card = page.card()
        ListRow.add(card, "🏙", "城市", CityPicker.label(prefs)) {
            CityPicker.show(this, prefs) { render() }
        }
        ListRow.add(
            card, "📍", "定位权限",
            if (hasLocationPermission()) "已授权" else "未授权"
        ) {
            if (hasLocationPermission()) {
                toast("已授权；不想用时可在系统设置里关闭")
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQ_LOCATION
                )
            }
        }
        page.hint("有定位时首页打开即查；没有定位时可用常去地点兜底。应用不在后台轮询。")

        page.section("其他")
        val other = page.card()
        ListRow.add(other, "ℹ️", "关于") {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        if (BuildConfig.DEBUG) {
            ListRow.add(other, "🛠", "开发者") {
                startActivity(Intent(this, DebugActivity::class.java))
            }
        }
        ListRow.add(other, "📦", "版本", BuildConfig.VERSION_NAME, chevron = false)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            render()
            if (!hasLocationPermission()) {
                toast("未授权，可继续用常去地点查询")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
