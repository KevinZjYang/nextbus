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
        ListRow.add(infoCard, "🏝", "超级岛", "媒体卡片通道（免授权）", chevron = false)
        ListRow.add(infoCard, "🔔", "到站提醒", "铃声跟随系统通知管理", chevron = false)
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
