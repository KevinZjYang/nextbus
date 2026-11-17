package com.yg.busisland

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import com.yg.busisland.ui.ListRow
import com.yg.busisland.ui.PageScaffold

/** 地点：只用于快速定位附近公交，不绑定订阅。 */
class PlacesActivity : com.yg.busisland.ui.PageActivity() {

    private lateinit var prefs: Prefs
    private lateinit var page: PageScaffold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = PageScaffold(
            this, "地点", "快速定位用，不绑定订阅",
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
        val places = prefs.places

        page.section("已添加 ${places.size} 个")
        val card = page.card()
        if (places.isEmpty()) {
            ListRow.add(card, "📍", "还没有地点", "点下面添加", chevron = false)
        }
        for (p in places) {
            val emoji = when (p.id) {
                "home" -> "🏠"
                "company" -> "🏢"
                else -> "📍"
            }
            val value = buildString {
                if (p.valid) {
                    append("%.5f, %.5f".format(p.lat, p.lng))
                } else {
                    append("未设坐标")
                }
                if (p.cityId.isNotBlank()) append(" · ").append(p.cityId)
            }
            ListRow.add(card, emoji, p.name.ifBlank { "地点" }, value) { menu(p) }
        }

        page.button("＋ 添加地点") { addPlace() }
        page.hint("地点只用于快速定位；订阅按线路 + 方向置顶，不绑地点。")
    }

    private fun menu(p: Place) {
        val items = arrayOf("查看附近公交", "重新选点", "重命名", "删除地点")
        AlertDialog.Builder(this)
            .setTitle(p.name.ifBlank { "地点" })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, NearbyLinesActivity::class.java)
                            .putExtra(NearbyLinesActivity.EXTRA_PLACE_ID, p.id)
                    )
                    1 -> pick(p.id)
                    2 -> rename(p)
                    else -> {
                        prefs.removePlace(p.id)
                        render()
                        toast("已删除地点，订阅不受影响")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rename(p: Place) {
        val box = EditText(this).apply { setText(p.name) }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.upsertPlace(p.copy(name = box.text.toString().trim().ifBlank { p.name }))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addPlace() {
        val box = EditText(this).apply { hint = "地点名，如 家 / 公司 / 学校" }
        AlertDialog.Builder(this)
            .setTitle("添加地点")
            .setView(box)
            .setPositiveButton("下一步") { _, _ ->
                val name = box.text.toString().trim().ifBlank { "地点" }
                val id = prefs.newPlaceId()
                prefs.upsertPlace(Place(id = id, name = name))
                pick(id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pick(id: String) {
        startActivity(
            Intent(this, PlacePickerActivity::class.java)
                .putExtra(PlacePickerActivity.EXTRA_PLACE_ID, id)
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
