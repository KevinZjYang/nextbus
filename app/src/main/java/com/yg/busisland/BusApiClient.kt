package com.yg.busisland

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 公交接口客户端（协议见 docs/protocol.md）。所有方法均为阻塞调用，请在后台线程执行。 */
object BusApiClient {

    private const val HOST = "api.chelaile.net.cn"
    private const val SIGN_KEY = "woqunimalegebi1234567890"
    private const val GCM_KEY = "5A3FC1D0BB4DFFE2D18BF10363BCE72C"
    private const val CRYPT_SUFFIX = "#qwihrnbtmj"
    private const val APP_VERSION = "7.6.8"
    private const val APP_VC = "436"

    @Volatile
    var lastUrl: String = ""
        private set

    @Volatile
    var lastBody: String = ""
        private set

    // ------------------------------------------------------------ crypto

    fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** sign = Base64(DESede/ECB/PKCS5(key, currentTimeMillis)) */
    fun requestSign(): String {
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(SIGN_KEY.toByteArray(Charsets.UTF_8), "DESede"))
        val out = cipher.doFinal(System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun inflate(p: Map<String, String>): String =
        p.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    private fun common(cityId: String?, udid: String): LinkedHashMap<String, String> {
        val m = linkedMapOf(
            "s" to "android",
            "v" to APP_VERSION,
            "vc" to APP_VC,
            "sv" to android.os.Build.VERSION.RELEASE.orEmpty().ifBlank { "13" },
            "udid" to udid,
            "sign" to requestSign()
        )
        if (!cityId.isNullOrBlank()) m["cityId"] = cityId
        return m
    }

    /** 按经纬度反查城市（localCity）；传入的是 GCJ-02（高德 / 常去地点）。 */
    fun localCity(lat: Double, lng: Double, udid: String): CityInfo? {
        val p = common(null, udid)
        p["lat"] = lat.toString()
        p["lng"] = lng.toString()
        p["gpstype"] = "gcj"
        val body = httpGet("https://$HOST/goocity/city/localCity?" + inflate(p))
        val jr = parseEnvelope(body)
        val c = jr.optJSONObject("data")?.optJSONObject("localCity") ?: return null
        val id = c.optString("cityId")
        if (id.isBlank()) return null
        return CityInfo(id, c.optString("cityName"), c.optDouble("lat", 0.0), c.optDouble("lng", 0.0), c.optString("pinyin"))
    }

    /** 鍩庡競鍒楄〃锛堢敤浜庢墜鍔ㄩ€夋嫨锛?*/
    fun cities(udid: String): List<CityInfo> {
        val p = common(null, udid)
        val body = httpGet("https://$HOST/goocity/city/moreCities?" + inflate(p))
        val jr = parseEnvelope(body)
        val arr = jr.optJSONObject("data")?.optJSONArray("cities") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = c.optString("cityId")
            if (id.isBlank()) null
            else CityInfo(id, c.optString("cityName"), c.optDouble("lat", 0.0), c.optDouble("lng", 0.0), c.optString("pinyin"))
        }
    }

    /** CryptoUtil.getUrl锛氳拷鍔?timeStamp & cryptoSignStr */
    private fun cryptoUrl(path: String, params: LinkedHashMap<String, String>): String {
        val t = System.currentTimeMillis()
        val udid = params["udid"] ?: ""
        val accountId = params["accountId"] ?: ""
        val sb = StringBuilder("action=").append(path)
        if (udid.isNotEmpty()) sb.append("&udid=").append(udid)
        if (accountId.isNotEmpty()) sb.append("&accountId=").append(accountId)
        sb.append("&timeStamp=").append(t).append(CRYPT_SUFFIX)

        val all = LinkedHashMap(params)
        all["timeStamp"] = t.toString()
        all["cryptoSignStr"] = md5(sb.toString())
        return "https://$HOST$path?" + inflate(all)
    }

    // ------------------------------------------------------------ http

    private fun httpGet(url: String): String {
        lastUrl = url
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "okhttp/4.9.0")
        conn.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            lastBody = body
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** **YGKJ{...}YGKJ## 鈫?JSONObject(jsonr)锛屽繀瑕佹椂瑙ｅ瘑 encryptResult */
    private fun parseEnvelope(body: String): JSONObject {
        val start = body.indexOf("**YGKJ")
        val end = body.indexOf("YGKJ##")
        if (start < 0 || end < 0) throw RuntimeException("鍝嶅簲鏍煎紡寮傚父: " + body.take(120))
        val json = JSONObject(body.substring(start + 6, end))
        val jr = json.optJSONObject("jsonr") ?: throw RuntimeException("缂哄皯 jsonr")
        val data = jr.optJSONObject("data")
        val enc = data?.optString("encryptResult").orEmpty()
        if (enc.isNotEmpty()) {
            val plain = decryptGcm(enc)
            jr.put("data", JSONObject(plain))
        }
        val status = jr.optString("status")
        if (status != "00") {
            throw RuntimeException(status + " " + jr.optString("errmsg"))
        }
        return jr
    }

    private fun decryptGcm(b64: String): String {
        val raw = Base64.decode(b64, Base64.DEFAULT)
        require(raw.size > 28) { "瀵嗘枃闀垮害寮傚父" }
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(raw.size - 16, raw.size)
        val ct = raw.copyOfRange(12, raw.size - 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(GCM_KEY.toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(128, iv)
        )
        val buf = ByteArray(ct.size + tag.size)
        System.arraycopy(ct, 0, buf, 0, ct.size)
        System.arraycopy(tag, 0, buf, ct.size, tag.size)
        return String(cipher.doFinal(buf), Charsets.UTF_8)
    }

    // ------------------------------------------------------------ api

    fun search(cityId: String, keyword: String, udid: String): List<JSONObject> {
        val p = common(cityId, udid)
        p["key"] = keyword
        p["fileds"] = "line"
        val body = httpGet("https://$HOST/bus/query!encryptedSearchMore.action?" + inflate(p))
        val jr = parseEnvelope(body)
        val result = jr.optJSONObject("data")?.optJSONObject("result") ?: return emptyList()
        val lines = result.optJSONArray("lines") ?: return emptyList()
        return (0 until lines.length()).mapNotNull { lines.optJSONObject(it) }
    }

    /** POI 鎼滅储锛氳繑鍥炲甫 gcj 缁忕含搴︾殑鍦扮偣锛岀敤浜庡/鍏徃閫夌偣 */
    fun searchPoi(cityId: String, keyword: String, udid: String): List<JSONObject> {
        val p = common(cityId, udid)
        p["key"] = keyword
        p["fileds"] = "poi"
        val body = httpGet("https://$HOST/bus/query!encryptedSearchMore.action?" + inflate(p))
        val jr = parseEnvelope(body)
        val pois = jr.optJSONObject("data")?.optJSONObject("result")?.optJSONArray("pois")
            ?: return emptyList()
        return (0 until pois.length()).mapNotNull { pois.optJSONObject(it) }
            .filter { it.optDouble("lat", 0.0) != 0.0 && it.optDouble("lng", 0.0) != 0.0 }
    }

    fun routeStations(cityId: String, lineId: String, lineName: String, udid: String): JSONArray =
        lineRouteData(cityId, lineId, lineName, udid).optJSONArray("stations") ?: JSONArray()

    /** 线路走向：stops 来自 stations[]，画线点优先 route[]。 */
    fun lineRoute(cityId: String, lineId: String, lineName: String, udid: String): LineRoute =
        LineRouteParser.parse(lineRouteData(cityId, lineId, lineName, udid))

    private fun lineRouteData(
        cityId: String,
        lineId: String,
        lineName: String,
        udid: String
    ): JSONObject {
        val p = common(cityId, udid)
        p["lineId"] = lineId
        p["lineName"] = lineName
        val body = httpGet("https://$HOST/bus/line!lineRoute.action?" + inflate(p))
        return parseEnvelope(body).optJSONObject("data") ?: JSONObject()
    }

    private val LOC_KEY = "5A3FC1D0BB4DFFE2D18BF10363BCE72C".toByteArray(Charsets.US_ASCII)

    /** encLoc = Base64(iv12 || AES-256-GCM("lat=..&lng=..&gpstype=gcj")) */
    fun encLoc(lat: Double, lng: Double): String {
        val plain = "lat=$lat&lng=$lng&gpstype=gcj"
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(LOC_KEY, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP)
    }

    /**
     * 附近物理站点。传入 GCJ-02；响应顶层的 gpstype 实测为 wgs，
     * 由 NearbyStationsParser 统一转回 GCJ 后再进入模型。
     */
    fun nearStations(cityId: String, lat: Double, lng: Double, udid: String): List<NearbyStation> {
        val p = common(cityId, udid)
        // 实测：该接口只认明文 lat/lng/gpstype，encLoc 完全被忽略
        // （传垃圾 encLoc 结果不变；不传明文只按 udid 走缓存，容易返回空）
        p["lat"] = lat.toString()
        p["lng"] = lng.toString()
        p["gpstype"] = "gcj"
        p["encLoc"] = encLoc(lat, lng)
        val body = httpGet("https://$HOST/bus/stop!encryptedNearPhysicalStns.action?" + inflate(p))
        val jr = parseEnvelope(body)
        return NearbyStationsParser.parse(jr.optJSONObject("data"))
    }

    /** 站点详情里的 stnStates[0] 直接带首班车到站时间，不用再查一次实时 */
    private fun etaOf(wrapper: JSONObject?): String {
        val st = wrapper?.optJSONArray("stnStates")?.optJSONObject(0) ?: return ""
        val arrival = st.optLong("arrivalTime", -1L)
        val travel = st.optLong("travelTime", -1L)
        return when {
            arrival > 0 -> {
                val mins = ((arrival - System.currentTimeMillis()) / 60_000L).toInt()
                when {
                    mins <= 0 -> "即将到站"
                    mins == 1 -> "1 分钟"
                    else -> "$mins 分钟"
                }
            }
            travel > 0 -> "${(travel / 60).toInt()} 分钟"
            else -> ""
        }
    }

    /** 某站点经过的线路 */
    fun stationLines(
        cityId: String,
        physicalStId: String,
        lat: Double,
        lng: Double,
        udid: String
    ): List<StationLine> {
        val p = common(cityId, udid)
        p["physicalStId"] = physicalStId
        p["encLoc"] = encLoc(lat, lng)
        val body = httpGet("https://$HOST/bus/stop!encryptedPhyStnDetail.action?" + inflate(p))
        val jr = parseEnvelope(body)
        val list = jr.optJSONObject("data")?.optJSONArray("stationList") ?: return emptyList()
        val out = mutableListOf<StationLine>()
        for (i in 0 until list.length()) {
            val st = list.optJSONObject(i) ?: continue
            val lines = st.optJSONArray("lines") ?: continue
            for (j in 0 until lines.length()) {
                val l = lines.optJSONObject(j)?.optJSONObject("line") ?: continue
                val lineId = l.optString("lineId")
                if (lineId.isBlank()) continue
                val wrapper = lines.optJSONObject(j)
                val order = wrapper?.optJSONObject("targetStation")?.optInt("order", 0) ?: 0
                val etaText = etaOf(wrapper)
                out.add(
                    StationLine(
                        sId = st.optString("sId"),
                        stationName = st.optString("sn"),
                        lineId = lineId,
                        lineNo = l.optString("lineNo").ifBlank { l.optString("name") },
                        lineName = l.optString("name"),
                        direction = l.optInt("direction"),
                        startSn = l.optString("startSn"),
                        endSn = l.optString("endSn"),
                        firstTime = l.optString("firstTime"),
                        lastTime = l.optString("lastTime"),
                        stationsNum = l.optInt("stationsNum"),
                        order = order,
                        etaText = etaText
                    )
                )
            }
        }
        return out
    }

    fun realtime(sub: LineSub, udid: String, lat: Double? = null, lng: Double? = null): RealTimeResult {
        val p = common(sub.cityId, udid)
        p["lineId"] = sub.lineId
        p["lineName"] = sub.lineName
        p["lineNo"] = sub.lineNo.ifBlank { sub.lineName }
        p["direction"] = sub.direction.toString()
        p["targetOrder"] = sub.targetOrder.toString()
        p["specialTargetOrder"] = "0"
        p["stationName"] = sub.stationName
        p["stationId"] = sub.stationId
        p["filter"] = "0"
        p["isNewLineDetail"] = "1"
        p["hpGnv"] = "1"
        if (lat != null && lng != null) {
            p["lat"] = lat.toString()
            p["lng"] = lng.toString()
            p["gpstype"] = "gcj"
        }

        val body = httpGet(cryptoUrl("/bus/line!encRefreshLineDetail.action", p))
        val jr = parseEnvelope(body)
        val data = jr.optJSONObject("data") ?: return RealTimeResult("", "", emptyList(), false, "空数据")

        var module: JSONObject? = null
        val modules = data.optJSONArray("modules")
        if (modules != null) {
            for (i in 0 until modules.length()) {
                val m = modules.optJSONObject(i)?.optJSONObject("data") ?: continue
                if (m.has("buses")) {
                    module = m
                    break
                }
            }
        }
        if (module == null) return RealTimeResult("", "", emptyList(), false, "无线路模块")

        return RealtimeParser.parse(module, sub.targetOrder)
    }

    /**
     * 线路详情里的全部在线车辆。实测 buses[] 与 lineRoute 同为 WGS-84，
     * 由 BusesParser 转成 GCJ-02 后再画到高德地图上；运营时间外返回空列表。
     */
    fun lineBuses(cityId: String, lineId: String, udid: String): List<BusPosition> {
        val p = common(cityId, udid)
        p["localCityId"] = cityId
        p["lineId"] = lineId
        val body = httpGet(cryptoUrl("/bus/line!encryptedLineDetail.action", p))
        val jr = parseEnvelope(body)
        return BusesParser.parse(jr.optJSONObject("data"))
    }

}
