package com.yg.busisland

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast

/** 城市选择：自动识别当前位置 / 用已设地点 / 搜索城市名。 */
object CityPicker {

    @Volatile
    private var cache: List<CityInfo> = emptyList()

    fun show(activity: Activity, prefs: Prefs, onPicked: (CityInfo) -> Unit = {}) {
        val items = arrayOf("自动检测（当前位置）", "用已设地点", "搜索城市名")
        AlertDialog.Builder(activity)
            .setTitle("当前城市：${label(prefs)}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> fromLocation(activity, prefs, onPicked)
                    1 -> fromPlace(activity, prefs, onPicked)
                    else -> search(activity, prefs, onPicked)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun label(prefs: Prefs): String =
        if (prefs.cityId.isBlank()) "未设置（点我选择）"
        else "${prefs.cityName} ${prefs.cityId}".trim()

    private fun fromLocation(activity: Activity, prefs: Prefs, onPicked: (CityInfo) -> Unit) {
        val fix = SystemLocator.lastGood(activity)
        if (fix == null) {
            toast(activity, "暂无可用定位，改用搜索城市名")
            return
        }
        resolve(activity, prefs, fix.lat, fix.lng, onPicked)
    }

    private fun fromPlace(activity: Activity, prefs: Prefs, onPicked: (CityInfo) -> Unit) {
        val place = prefs.places.firstOrNull { it.valid }
        if (place == null) {
            toast(activity, "还没有设点的地点，先添加家或公司")
            return
        }
        resolve(activity, prefs, place.lat, place.lng, onPicked)
    }

    private fun resolve(
        activity: Activity,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        onPicked: (CityInfo) -> Unit
    ) {
        toast(activity, "识别城市中…")
        Thread {
            val city = runCatching { BusApiClient.localCity(lat, lng, prefs.udid) }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (city == null) {
                    toast(activity, "识别失败，请手动搜索")
                    return@runOnUiThread
                }
                save(prefs, city)
                toast(activity, "城市：${city.name}（${city.id}）")
                onPicked(city)
            }
        }.start()
    }

    private fun search(activity: Activity, prefs: Prefs, onPicked: (CityInfo) -> Unit) {
        val box = EditText(activity).apply { hint = "城市名，如 北京 / 上海" }
        AlertDialog.Builder(activity)
            .setTitle("搜索城市")
            .setView(box)
            .setPositiveButton("搜索") { _, _ ->
                val key = box.text.toString().trim()
                if (key.isBlank()) return@setPositiveButton
                toast(activity, "加载城市列表…")
                Thread {
                    val list = if (cache.isEmpty()) {
                        runCatching { BusApiClient.cities(prefs.udid) }.getOrDefault(emptyList())
                    } else {
                        cache
                    }
                    if (cache.isEmpty() && list.isNotEmpty()) cache = list
                    val hits = list.filter {
                        it.name.contains(key) || it.pinyin.contains(key, ignoreCase = true)
                    }.take(30)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (hits.isEmpty()) {
                            toast(activity, "没有匹配的城市")
                            return@runOnUiThread
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("选择城市")
                            .setItems(hits.map { "${it.name}（${it.id}）" }.toTypedArray()) { _, index ->
                                val city = hits[index]
                                save(prefs, city)
                                toast(activity, "城市：${city.name}（${city.id}）")
                                onPicked(city)
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save(prefs: Prefs, city: CityInfo) {
        prefs.saveCity(city)
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
