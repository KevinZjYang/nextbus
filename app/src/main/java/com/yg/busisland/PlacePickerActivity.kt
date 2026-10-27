package com.yg.busisland

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 家/公司选点：搜索地点 / 一键定位 / 粘贴坐标 / 手动输入。
 */
class PlacePickerActivity : androidx.appcompat.app.AppCompatActivity(), LocationListener {

    companion object {
        const val EXTRA_WHICH = "which"   // 兼容旧调用
        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_QUERY = "q"       // 调试用：预填关键词
    }

    private lateinit var prefs: Prefs
    private lateinit var placeId: String
    private lateinit var placeName: String
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var waitingFix = false
    private var cancelSignals = mutableListOf<android.os.CancellationSignal>()
    private var waitStart = 0L
    private var waitTicker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        placeId = intent.getStringExtra(EXTRA_PLACE_ID).orEmpty()
        if (placeId.isBlank()) {
            val legacy = intent.getStringExtra(EXTRA_WHICH).orEmpty()
            placeId = when (legacy) {
                "home" -> "home"
                "company" -> "company"
                else -> prefs.newPlaceId()
            }
        }
        placeName = prefs.placeById(placeId)?.name.orEmpty().ifBlank {
            when (placeId) {
                "home" -> "家"
                "company" -> "公司"
                else -> "地点"
            }
        }
        buildUi()

        val q = intent.getStringExtra(EXTRA_QUERY)
        if (!q.isNullOrBlank()) {
            searchBox.setText(q)
            handler.postDelayed({ doSearch(q) }, 300)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopWait()
        super.onDestroy()
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this@PlacePickerActivity,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                    0xFFF2F2F7.toInt()
                )
            )
            setPadding(0, Ui.statusBarHeight(this@PlacePickerActivity), 0, 0)
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(16), dp(4))
        }
        bar.addView(com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "‹"
            textSize = 26f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "设置$placeName"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
        })
        page.addView(bar)

        // 搜索卡
        val searchCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = 0x14000000
            setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
        }
        val searchBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        searchCard.addView(searchBody)
        page.addView(searchCard, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(14), dp(2), dp(14), dp(10))
        })

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        searchBox = EditText(this).apply {
            hint = "小区 / 写字楼 / 地铁站"
            isSingleLine = true
            textSize = 15f
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(text.toString().trim())
                    true
                } else false
            }
        }
        searchRow.addView(searchBox)
        searchRow.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "搜索"
            isAllCaps = false
            textSize = 14f
            setOnClickListener { doSearch(searchBox.text.toString().trim()) }
        })
        searchBody.addView(searchRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                text = "📍 用当前位置"
                isAllCaps = false
                textSize = 14f
                setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt()))
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt()))
                setOnClickListener { useCurrentLocation() }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
        )
        actions.addView(
            com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "📋 粘贴坐标"
                isAllCaps = false
                textSize = 14f
                setOnClickListener { pasteCoords() }
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        searchBody.addView(actions)

        searchBody.addView(com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "手动输入经纬度"
            isAllCaps = false
            textSize = 13f
            setOnClickListener { manualInput() }
        })

        val cur = prefs.placeById(placeId)
        if (cur != null && (cur.valid || cur.name.isNotBlank())) {
            searchBody.addView(TextView(this).apply {
                text = "当前：${cur.name}  ${"%.5f".format(cur.lat)}, ${"%.5f".format(cur.lng)}"
                textSize = 12f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(4), 0, 0)
            })
        }

        // 状态行
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(2), dp(22), dp(4))
        }
        status = TextView(this).apply {
            textSize = 12.5f
            text = "搜索地点，或直接定位"
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        statusRow.addView(status)
        statusRow.addView(progress)
        page.addView(statusRow)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(28))
        }
        val scroll = ScrollView(this)
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(page)
    }

    private fun attrColor(attr: Int, fallback: Int = Color.BLACK): Int =
        com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ 搜索

    private fun cityId(): String = prefs.cityId.ifBlank { "" }

    private fun doSearch(keyword: String) {
        if (keyword.isBlank()) {
            toast("请输入关键词")
            return
        }
        val cid = cityId()
        if (cid.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("还没有确定城市")
                .setMessage("搜索地点需要先选城市。可以回主页点「城市」设置，或先用当前定位。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        showLoading(true, "在${prefs.cityName.ifBlank { "当前城市" }}搜索中…")
        list.removeAllViews()

        Thread {
            val res = runCatching { BusApiClient.searchPoi(cid, keyword, prefs.udid) }
            runOnUiThread {
                showLoading(false, "")
                res.onFailure {
                    status.text = "搜索失败：${it.message}"
                }.onSuccess { pois ->
                    if (pois.isEmpty()) {
                        status.text = "没有找到「$keyword」，换个更具体的名称试试"
                        return@onSuccess
                    }
                    status.text = "共 ${pois.size} 个结果，点击设为$placeName"
                    renderPois(pois, cid)
                }
            }
        }.start()
    }

    private fun renderPois(pois: List<JSONObject>, cid: String) {
        list.removeAllViews()
        for (p in pois) {
            val name = p.optString("sn1").ifBlank { p.optString("sn") }
            val addr = p.optString("sn1Address").ifBlank { p.optString("address") }
            val tag = p.optString("sn1Tag")
            val lat = p.optDouble("lat", 0.0)
            val lng = p.optDouble("lng", 0.0)

            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = 0x14000000
                isClickable = true
                isFocusable = true
                setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
                setOnClickListener { confirmSave(name, lat, lng, cid) }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            box.addView(TextView(this).apply {
                text = buildString {
                    append(name)
                    if (tag.isNotBlank()) append("   ").append(tag)
                }
                textSize = 15.5f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            })
            if (addr.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = addr
                    textSize = 12.5f
                    setPadding(0, dp(2), 0, 0)
                    setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
            }
            card.addView(box)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun confirmSave(defaultName: String, lat: Double, lng: Double, cid: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val name = EditText(this).apply { setText(defaultName) }
        box.addView(name)
        box.addView(TextView(this).apply {
            text = "经纬度：${"%.6f".format(lat)}, ${"%.6f".format(lng)}"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
        })

        AlertDialog.Builder(this)
            .setTitle("保存为$placeName")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val place = Place(
                    id = placeId,
                    name = name.text.toString().ifBlank { defaultName },
                    lat = lat, lng = lng,
                    cityId = cid
                )
                prefs.upsertPlace(place)
                resolveCityFrom(lat, lng, cid, placeId)
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用该点的坐标反查城市，保证后续搜索用对城市 */
    private fun resolveCityFrom(lat: Double, lng: Double, fallbackCityId: String, pid: String) {
        Thread {
            val c = runCatching { BusApiClient.localCity(lat, lng, prefs.udid) }.getOrNull()
            if (c != null) {
                prefs.cityId = c.id
                prefs.cityName = c.name
                prefs.cityLat = c.lat
                prefs.cityLng = c.lng
            } else if (fallbackCityId.isNotBlank()) {
                prefs.cityId = fallbackCityId
            }
            if (c != null) {
                prefs.placeById(pid)?.let { prefs.upsertPlace(it.copy(cityId = c.id)) }
            }
        }.start()
    }

    // ------------------------------------------------------------ 定位

    private fun useCurrentLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ), 200
            )
            return
        }
        if (AmapLocator.available) {
            showLoading(true, "高德定位中…")
            AmapLocator.once(this, 15_000) { loc ->
                if (loc != null) {
                    showLoading(false, "高德定位成功（精度 ${loc.accuracy.toInt()}m）")
                    onFix(loc, 0)
                } else {
                    showLoading(false, "高德定位失败，改用系统定位")
                    systemLocate()
                }
            }
            return
        }
        systemLocate()
    }

    private fun systemLocate() {
        val mgr = (getSystemService(LOCATION_SERVICE) as LocationManager)
        lm = mgr

        val enabled = if (android.os.Build.VERSION.SDK_INT >= 28) mgr.isLocationEnabled else true
        if (!enabled) {
            AlertDialog.Builder(this)
                .setTitle("系统定位已关闭")
                .setMessage("请打开「位置信息」再试，或直接搜索地点 / 粘贴坐标。")
                .setPositiveButton("去打开") { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val cached = bestLastKnown(mgr)
        if (cached != null && System.currentTimeMillis() - cached.time <= 10 * 60_000) {
            onFix(cached, 0)
            return
        }

        waitingFix = true
        waitStart = System.currentTimeMillis()
        showLoading(true, "定位中… 0s（室内建议到窗边）")
        startTicker()

        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                for (p in listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                    if (!runCatching { mgr.isProviderEnabled(p) }.getOrDefault(false)) continue
                    val cs = android.os.CancellationSignal()
                    cancelSignals.add(cs)
                    mgr.getCurrentLocation(p, cs, mainExecutor) { loc ->
                        if (loc != null) onFix(loc, 0)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                mgr.requestSingleUpdate("fused", this, Looper.getMainLooper())
                @Suppress("DEPRECATION")
                mgr.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            finishWait("定位启动失败：${e.message}")
            return
        }

        handler.postDelayed({ if (waitingFix) onTimeout(cached) }, 25_000)
    }

    private fun bestLastKnown(mgr: LocationManager): Location? {
        return listOf(
            "fused",
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { p -> runCatching { mgr.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun startTicker() {
        val r = object : Runnable {
            override fun run() {
                if (!waitingFix) return
                val s = (System.currentTimeMillis() - waitStart) / 1000
                status.text = "定位中… ${s}s（室内建议到窗边/室外）"
                handler.postDelayed(this, 1000)
            }
        }
        waitTicker = r
        handler.postDelayed(r, 1000)
    }

    private fun stopWait() {
        waitingFix = false
        waitTicker?.let { handler.removeCallbacks(it) }
        cancelSignals.forEach { runCatching { it.cancel() } }
        cancelSignals.clear()
        runCatching { lm?.removeUpdates(this) }
    }

    private fun finishWait(msg: String) {
        stopWait()
        showLoading(false, msg)
    }

    private fun onFix(loc: Location, ageMin: Int) {
        stopWait()
        val acc = if (loc.hasAccuracy()) loc.accuracy.toInt() else -1
        showLoading(false, "已定位（精度 ${if (acc >= 0) "${acc}m" else "未知"}）")
        val prefix = if (ageMin > 1) "（${ageMin} 分钟前的位置）" else ""
        confirmSave(
            placeName + prefix,
            loc.latitude, loc.longitude, cityId()
        )
    }

    private fun onTimeout(cached: Location?) {
        stopWait()
        val late = lm?.let { bestLastKnown(it) }
        val options = mutableListOf<String>()
        if (cached != null || late != null) options.add("用最近一次缓存位置")
        options.add("重新定位（25s）")
        options.add("改用搜索地点")
        options.add("粘贴坐标")

        AlertDialog.Builder(this)
            .setTitle("定位超时（25 秒无新定位）")
            .setItems(options.toTypedArray()) { _, i ->
                when (options[i]) {
                    "用最近一次缓存位置" -> onFix((late ?: cached)!!, 999)
                    "重新定位（25s）" -> useCurrentLocation()
                    "改用搜索地点" -> {
                        searchBox.requestFocus()
                        toast("输入小区/写字楼名称即可")
                    }
                    else -> pasteCoords()
                }
            }
            .setNegativeButton("取消", null)
            .show()
        showLoading(false, "定位超时")
    }

    override fun onLocationChanged(location: Location) {
        if (waitingFix) onFix(location, 0)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            useCurrentLocation()
        } else {
            toast("未授权定位，可改用搜索或粘贴坐标")
        }
    }

    // ------------------------------------------------------------ 粘贴 / 手动

    private fun pasteCoords() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val raw = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        val parsed = parseCoords(raw)
        if (parsed == null) {
            toast("剪贴板里没有识别到坐标，例如：116.397,39.908")
            return
        }
        val (lat, lng) = parsed
        confirmSave(placeName, lat, lng, cityId())
    }

    /** 支持 116.397,39.908 / 39.908,116.397 / lat:39.9 lng:116.3 等 */
    private fun parseCoords(text: String): Pair<Double, Double>? {
        if (text.isBlank()) return null
        val nums = Regex("""-?\d{1,3}\.\d{3,}""").findAll(text).map { it.value.toDouble() }.toList()
        if (nums.size < 2) return null
        var a = nums[0]
        var b = nums[1]
        // 判断经纬度顺序：纬度绝对值 ≤ 90
        if (kotlin.math.abs(a) > 90 && kotlin.math.abs(b) <= 90) {
            val t = a; a = b; b = t
        }
        if (kotlin.math.abs(a) > 90 || kotlin.math.abs(b) > 180) return null
        return a to b
    }

    private fun manualInput() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val lat = EditText(this).apply {
            hint = "纬度 lat"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lng = EditText(this).apply {
            hint = "经度 lng"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        box.addView(lat)
        box.addView(lng)
        AlertDialog.Builder(this)
            .setTitle("手动输入经纬度")
            .setView(box)
            .setPositiveButton("下一步") { _, _ ->
                val la = lat.text.toString().toDoubleOrNull()
                val ln = lng.text.toString().toDoubleOrNull()
                if (la == null || ln == null) {
                    toast("请输入有效数字")
                    return@setPositiveButton
                }
                confirmSave(placeName, la, ln, cityId())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLoading(on: Boolean, text: String) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (text.isNotBlank()) status.text = text
    }

}
