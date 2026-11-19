package com.yg.busisland

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold

/** 关于：版本、构建类型、协议说明、开源许可 */
class AboutActivity : com.yg.busisland.ui.PageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = PageScaffold(this, "关于", null, showBack = true, onBack = { finish() })

        page.section("应用")
        val appCard = page.card()
        ListRow.add(appCard, "🚌", "下一班", "v${BuildConfig.VERSION_NAME}", chevron = false)
        ListRow.add(
            appCard, "🔧", "构建类型",
            if (BuildConfig.DEBUG) "debug" else "release", chevron = false
        )
        ListRow.add(appCard, "📦", "包名", BuildConfig.APPLICATION_ID, chevron = false)

        page.section("说明")
        val infoCard = page.card()
        ListRow.add(infoCard, "🚏", "查询方式", "打开应用时前台查附近公交与实时到站", chevron = false)
        ListRow.add(infoCard, "★", "订阅", "按线路 + 方向置顶，不绑地点", chevron = false)
        ListRow.add(infoCard, "🕒", "下一班", "首页大卡、详情、列表与地图共用实时到站", chevron = false)
        ListRow.add(infoCard, "📄", "接口协议", "docs/protocol.md", chevron = false)

        page.section("源码")
        page.button("在 GitHub 上查看", primary = false) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/KevinZjYang/nextbus"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { toast("没有可用的浏览器") }
        }

        page.section("许可")
        page.hint("MIT License · 接口参数由样本 APK 逆向分析得到，仅供个人学习与自用。")

        setContentView(page.root)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
