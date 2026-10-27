package com.yg.busisland

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** 本地配置（SharedPreferences）。 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("bus_island", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var askedPermissions: Boolean
        get() = sp.getBoolean("asked_perm", false)
        set(v) = sp.edit().putBoolean("asked_perm", v).apply()

    var radius: Int
        get() = sp.getInt("radius", 800)
        set(v) = sp.edit().putInt("radius", v).apply()

    var intervalSec: Int
        get() = sp.getInt("interval", 45)
        set(v) = sp.edit().putInt("interval", v).apply()

    var islandEnabled: Boolean
        get() = sp.getBoolean("island", true)
        set(v) = sp.edit().putBoolean("island", v).apply()

    /** true=媒体通知上岛（兼容模式），false=miui.focus.param */
    /** 调试：忽略通勤时段/围栏，用来随时看岛 */
    var debugMode: Boolean
        get() = sp.getBoolean("debug_mode", false)
        set(v) = sp.edit().putBoolean("debug_mode", v).apply()


    /** 到站提醒：剩余 N 分钟时弹通知 */
    var remindEnabled: Boolean
        get() = sp.getBoolean("remind_enabled", true)
        set(v) = sp.edit().putBoolean("remind_enabled", v).apply()

    /** 自选提示音（content:// 或 file://），空 = 系统默认通知音 */
    var remindSoundUri: String
        get() = sp.getString("remind_sound_uri", "") ?: ""
        set(v) = sp.edit().putString("remind_sound_uri", v).apply()

    /** 岛出现时是否响一声 */
    var islandSound: Boolean
        get() = sp.getBoolean("island_sound", true)
        set(v) = sp.edit().putBoolean("island_sound", v).apply()

    /** 到站提醒是否响铃 */
    var remindSound: Boolean
        get() = sp.getBoolean("remind_sound", true)
        set(v) = sp.edit().putBoolean("remind_sound", v).apply()

    var remindMinutes: Int
        get() = sp.getInt("remind_minutes", 3)
        set(v) = sp.edit().putInt("remind_minutes", v).apply()

    var lastRemindKey: String
        get() = sp.getString("last_remind_key", "") ?: ""
        set(v) = sp.edit().putString("last_remind_key", v).apply()

    var lastRemindAt: Long
        get() = sp.getLong("last_remind_at", 0L)
        set(v) = sp.edit().putLong("last_remind_at", v).apply()

    /** 临时查询截止时间：此时间之前忽略时段/围栏，直接出岛 */
    var adhocUntil: Long
        get() = sp.getLong("adhoc_until", 0L)
        set(v) = sp.edit().putLong("adhoc_until", v).apply()

    // 上一次成功结果（失败时兜底显示）
    var lastOkAt: Long
        get() = sp.getLong("last_ok_at", 0L)
        set(v) = sp.edit().putLong("last_ok_at", v).apply()

    var lastOkTitle: String
        get() = sp.getString("last_ok_title", "") ?: ""
        set(v) = sp.edit().putString("last_ok_title", v).apply()

    var lastOkEta: String
        get() = sp.getString("last_ok_eta", "") ?: ""
        set(v) = sp.edit().putString("last_ok_eta", v).apply()

    var lastOkContent: String
        get() = sp.getString("last_ok_content", "") ?: ""
        set(v) = sp.edit().putString("last_ok_content", v).apply()

    var udid: String
        get() {
            val cur = sp.getString("udid", null)
            if (!cur.isNullOrBlank()) return cur
            val gen = UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString("udid", gen).apply()
            return gen
        }
        set(v) = sp.edit().putString("udid", v).apply()

    var home: Place?
        get() = Place.fromJson(readJson("home"))
        set(v) = writeJson("home", v?.toJson())

    var company: Place?
        get() = Place.fromJson(readJson("company"))
        set(v) = writeJson("company", v?.toJson())

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

    fun removePlace(id: String) {
        places = places.filterNot { it.id == id }
        lines = lines.filterNot { it.place == id }.toMutableList()
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
        sp.edit().putString("places", placesToJson(merged)).putBoolean("places_migrated", true).apply()
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

    var homeCity: String
        get() = sp.getString("home_city", "") ?: ""
        set(v) = sp.edit().putString("home_city", v).apply()

    var companyCity: String
        get() = sp.getString("company_city", "") ?: ""
        set(v) = sp.edit().putString("company_city", v).apply()

    // 当前城市（搜索、线路都用它）
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

    var schedule: Schedule
        get() = Schedule.fromJson(readJson("schedule"))
        set(v) = writeJson("schedule", v.toJson())

    var lines: MutableList<LineSub>
        get() = LineSub.listFromJson(sp.getString("lines", null))
        set(v) = sp.edit().putString("lines", LineSub.listToJson(v)).apply()

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
        sp.edit().apply { if (o == null) remove(key) else putString(key, o.toString()) }.apply()
    }
}
