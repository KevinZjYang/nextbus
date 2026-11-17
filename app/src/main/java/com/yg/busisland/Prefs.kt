package com.yg.busisland

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** 本地配置（SharedPreferences）。 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("bus_island", Context.MODE_PRIVATE)

    var askedPermissions: Boolean
        get() = sp.getBoolean("asked_perm", false)
        set(v) = sp.edit().putBoolean("asked_perm", v).apply()

    var udid: String
        get() {
            val cur = sp.getString("udid", null)
            if (!cur.isNullOrBlank()) return cur
            val gen = UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString("udid", gen).apply()
            return gen
        }
        set(v) = sp.edit().putString("udid", v).apply()

    // ---------------- 常去地点（家/公司/学校…）

    var places: List<Place>
        get() = parsePlaces(sp.getString("places", "") ?: "")
        set(v) = sp.edit().putString("places", placesToJson(v)).apply()

    fun placeById(id: String): Place? = places.firstOrNull { it.id == id }

    fun upsertPlace(p: Place) {
        val list = places.toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        places = list
    }

    /** 只删地点；订阅不再绑定地点，不能跟着删。 */
    fun removePlace(id: String) {
        places = places.filterNot { it.id == id }
    }

    fun newPlaceId(): String = "p" + System.currentTimeMillis().toString(36)

    /** 旧版 home/company 迁移到 places（只跑一次） */
    fun migrateLegacyPlaces() {
        if (sp.getBoolean("places_migrated", false)) return
        val merged = places.toMutableList()
        fun addOne(id: String, p: Place?, city: String) {
            if (p == null || (!p.valid && p.name.isBlank())) return
            if (merged.any { it.id == id }) return
            merged.add(p.copy(id = id, cityId = p.cityId.ifBlank { city }))
        }
        addOne("home", home, homeCity)
        addOne("company", company, companyCity)
        sp.edit().putString("places", placesToJson(merged))
            .putBoolean("places_migrated", true).apply()
    }

    private fun parsePlaces(raw: String): List<Place> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { Place.fromJson(arr.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun placesToJson(list: List<Place>): String {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    var home: Place?
        get() = Place.fromJson(readJson("home"))
        set(v) = writeJson("home", v?.toJson())

    var company: Place?
        get() = Place.fromJson(readJson("company"))
        set(v) = writeJson("company", v?.toJson())

    var homeCity: String
        get() = sp.getString("home_city", "") ?: ""
        set(v) = sp.edit().putString("home_city", v).apply()

    var companyCity: String
        get() = sp.getString("company_city", "") ?: ""
        set(v) = sp.edit().putString("company_city", v).apply()

    // 当前城市（搜索、线路都用它；有定位时会被自动识别覆盖）
    var cityId: String
        get() = sp.getString("city_id", "") ?: ""
        set(v) = sp.edit().putString("city_id", v).apply()

    var cityName: String
        get() = sp.getString("city_name", "") ?: ""
        set(v) = sp.edit().putString("city_name", v).apply()

    var cityLat: Double
        get() = sp.getFloat("city_lat", 0f).toDouble()
        set(v) = sp.edit().putFloat("city_lat", v.toFloat()).apply()

    var cityLng: Double
        get() = sp.getFloat("city_lng", 0f).toDouble()
        set(v) = sp.edit().putFloat("city_lng", v.toFloat()).apply()

    fun saveCity(c: CityInfo) {
        cityId = c.id
        cityName = c.name
        cityLat = c.lat
        cityLng = c.lng
    }

    // ---------------- 订阅（按线路 + 方向，不绑地点）

    var lines: MutableList<LineSub>
        get() = LineSub.listFromJson(sp.getString("lines", null))
        set(v) = sp.edit().putString("lines", LineSub.listToJson(v)).apply()

    /** 已存在同一线路同一方向时返回 false。 */
    fun addLine(sub: LineSub): Boolean {
        val list = lines
        if (list.any { it.lineId == sub.lineId && it.direction == sub.direction }) return false
        list.add(sub)
        lines = list
        return true
    }

    fun removeLine(lineId: String, direction: Int) {
        lines = lines.filterNot {
            it.lineId == lineId && it.direction == direction
        }.toMutableList()
    }

    fun isSubscribed(lineId: String, direction: Int): Boolean =
        lines.any { it.lineId == lineId && it.direction == direction }

    /** 老版本可能同一线路绑了多个地点，升级时压成一条。 */
    fun dedupeLinesOnce() {
        if (sp.getBoolean("lines_deduped_v2", false)) return
        val seen = HashSet<Pair<String, Int>>()
        val deduped = lines.filter { seen.add(it.lineId to it.direction) }
        lines = deduped.toMutableList()
        sp.edit().putBoolean("lines_deduped_v2", true).apply()
    }

    var lastStatus: String
        get() = sp.getString("last_status", "") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()

    var lastQueryUrl: String
        get() = sp.getString("last_url", "") ?: ""
        set(v) = sp.edit().putString("last_url", v).apply()

    var lastQueryBody: String
        get() = sp.getString("last_body", "") ?: ""
        set(v) = sp.edit().putString("last_body", v).apply()

    private fun readJson(key: String): JSONObject? {
        val s = sp.getString(key, null) ?: return null
        return runCatching { JSONObject(s) }.getOrNull()
    }

    private fun writeJson(key: String, o: JSONObject?) {
        sp.edit().apply {
            if (o == null) remove(key) else putString(key, o.toString())
        }.apply()
    }
}
