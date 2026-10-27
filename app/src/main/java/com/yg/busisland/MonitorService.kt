package com.yg.busisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 前台服务：通勤时段内监测位置，进入家/公司围栏后轮询订阅线路并刷新超级岛。
 */
class MonitorService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.yg.busisland.action.START"
        const val ACTION_STOP = "com.yg.busisland.action.STOP"
        const val ACTION_REFRESH = "com.yg.busisland.action.REFRESH"

        private const val FG_ID = 1000
        private const val FG_CHANNEL = "bus_monitor"

        fun start(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private var lm: LocationManager? = null
    private var lastLocation: Location? = null
    private var lastPollAt = 0L
    private var rotateIdx = 0
    private var lastAmapAt = 0L
    private var polling = false
    private var islandShown = false
    private var lastIslandKey = ""
    private var tickDelayMs = 20_000L
    private var trackingCoarse: Boolean? = null
    private var lastEnsureAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (evaluate()) handler.postDelayed(this, tickDelayMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        HyperIsland.ensureChannel(this)
        createFgChannel()
        Notify.cleanupOldChannels(this)
        startForeground(FG_ID, fgNotification("准备中…"))
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                lastPollAt = 0
                evaluate()
                return START_STICKY
            }
        }
        if (!prefs.enabled && System.currentTimeMillis() >= prefs.adhocUntil) {
            shutdown()
            return START_NOT_STICKY
        }
        startForeground(FG_ID, fgNotification("通勤监控运行中"))
        handler.removeCallbacks(tick)
        handler.post(tick)
        Scheduler.armNext(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { lm?.removeUpdates(this) }
        pool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 定位

    /**
     * 只在通勤时段内开启定位；距离过远时用「粗定位」（只 fused/network，3 分钟一次），省电。
     */
    private fun startTracking(coarse: Boolean = false) {
        if (trackingCoarse == coarse) return
        stopTracking()
        val mgr = lm ?: return
        val interval = if (coarse) 180_000L else 60_000L
        val dist = if (coarse) 500f else 100f
        try {
            runCatching {
                if (mgr.isProviderEnabled("fused")) {
                    mgr.requestLocationUpdates("fused", interval, dist, this, Looper.getMainLooper())
                }
            }
            if (!coarse && mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, dist, this, Looper.getMainLooper())
            }
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, interval, dist, this, Looper.getMainLooper())
            }
            trackingCoarse = coarse
        } catch (e: SecurityException) {
            prefs.lastStatus = "缺少定位权限"
        }
    }

    private fun stopTracking() {
        if (trackingCoarse == null) return
        runCatching { lm?.removeUpdates(this) }
        trackingCoarse = null
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    /** 缓存过旧时补一次（优先高德，其次 fused/network/gps 的 lastKnown + 一次 getCurrentLocation） */
    private fun ensureLocation() {
        val now = System.currentTimeMillis()
        if (now - lastEnsureAt < 60_000) return
        lastEnsureAt = now
        val mgr = lm ?: return
        val cur = lastLocation
        if (cur != null && now - cur.time < 5 * 60_000) return
        if (AmapLocator.available && System.currentTimeMillis() - lastAmapAt > 5 * 60_000) {
            lastAmapAt = System.currentTimeMillis()
            AmapLocator.once(this, 15_000) { loc -> if (loc != null) lastLocation = loc }
        }
        val best = listOf(
            "fused",
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { p -> runCatching { mgr.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null && (cur == null || best.time > cur.time)) lastLocation = best
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            for (p in listOf("fused", LocationManager.NETWORK_PROVIDER)) {
                runCatching {
                    mgr.getCurrentLocation(p, null, mainExecutor) { loc ->
                        if (loc != null) lastLocation = loc
                    }
                }
            }
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ------------------------------------------------------------ 主循环

    private fun evaluate(): Boolean {
        val adhocActive = System.currentTimeMillis() < prefs.adhocUntil
        if (!prefs.enabled && !adhocActive) {
            shutdown()
            return false
        }
        if (prefs.debugMode) {
            startTracking(coarse = false)
            evaluateDebug()
            tickDelayMs = 20_000L
            return true
        }
        // 临时查询：忽略时段与围栏，10 分钟内有效
        val nowMs = System.currentTimeMillis()
        if (adhocActive) {
            startTracking(coarse = false)
            ensureLocation()
            val subs = prefs.lines
            if (subs.isEmpty()) {
                setStatus("临时查询：还没有订阅线路")
            } else if (!polling && nowMs - lastPollAt >= 15_000) {
                lastPollAt = nowMs
                setStatus("临时查询中…")
                poll("adhoc", "临时", subs, lastLocation)
            }
            tickDelayMs = 20_000L
            return true
        }
        val cal = Calendar.getInstance()
        if (!prefs.schedule.activeAt(cal)) {
            // 非通勤时段：关定位、撤岛、撤前台通知，只留闹钟在下一个时段起点唤醒
            stopTracking()
            cancelIsland()
            setStatus("非通勤时段，已暂停")
            Scheduler.armNext(this)
            handler.removeCallbacks(tick)
            stopForeground(true)
            stopSelf()
            return false
        }

        val places = prefs.places
        val radius = prefs.radius.toFloat()
        if (places.isEmpty()) {
            cancelIsland()
            setStatus("还没有设置常去地点")
            tickDelayMs = 10 * 60_000L
            return true
        }

        // 先用缓存位置判断远近，决定用粗定位还是精定位
        val cached = lastLocation?.let { nearestDistance(it, places) }
        val coarseNow = cached != null && cached > radius * 2
        startTracking(coarse = coarseNow)
        ensureLocation()

        val loc = lastLocation
        if (loc == null) {
            tickDelayMs = 20_000L
            setStatus("等待定位…")
            return true
        }

        val (nearest, dist) = nearestPlace(loc, places)
        val far = dist > radius * 2
        tickDelayMs = when {
            dist <= radius -> 20_000L
            far -> 120_000L
            else -> 40_000L
        }
        if (dist > radius) {
            cancelIsland()
            setStatus(
                if (far) "距常去地点 ${"%.0f".format(dist / 1000)} km，已降频监测"
                else "不在家/公司附近（${dist.toInt()} m）"
            )
            return true
        }

        val place = nearest ?: return true
        val placeKey = place.id
        val placeName = place.name
        val subs = prefs.lines.filter { it.place == placeKey }
        if (subs.isEmpty()) {
            setStatus("已到${placeName.ifBlank { "地点" }}，未订阅线路")
            cancelIsland()
            return true
        }
        val now = System.currentTimeMillis()
        val intervalMs = (if (dist > radius * 0.7f) prefs.intervalSec * 2 else prefs.intervalSec) * 1000L
        if (polling || now - lastPollAt < intervalMs) return true
        lastPollAt = now
        poll(placeKey, placeName, subs, loc)
        return true
    }

    private fun nearestPlace(loc: Location, places: List<Place>): Pair<Place?, Float> {
        var best: Place? = null
        var bestD = Float.MAX_VALUE
        for (p in places) {
            if (!p.valid) continue
            val d = distance(loc, p)
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        return best to bestD
    }

    private fun nearestDistance(loc: Location, places: List<Place>): Float =
        nearestPlace(loc, places).second

    /** 调试：忽略时段与围栏，随时出岛；没订阅线路就发示例岛 */
    private fun evaluateDebug() {
        val subs = prefs.lines
        if (subs.isEmpty()) {
            HyperIsland.showMedia(this, "1 路", "3 分钟", "往 新首钢 · 下一班 8 分钟")
            setStatus("调试：示例岛（尚未订阅线路）")
            return
        }
        val now = System.currentTimeMillis()
        if (polling || now - lastPollAt < 15_000) return
        lastPollAt = now
        poll("debug", "调试", subs, lastLocation)
    }

    private fun poll(placeKey: String, placeName: String, subs: List<LineSub>, loc: Location?) {
        polling = true
        val udid = prefs.udid
        pool.execute {
            val results = mutableListOf<Pair<LineSub, RealTimeResult>>()
            for (s in subs) {
                val r = try {
                    ChelaileClient.realtime(s, udid, loc?.latitude, loc?.longitude)
                } catch (e: Exception) {
                    RealTimeResult("", "", emptyList(), false, e.message ?: "请求失败")
                }
                results.add(s to r)
            }
            handler.post { render(placeName, results) }
        }
    }

    private fun render(placeName: String, results: List<Pair<LineSub, RealTimeResult>>) {
        polling = false
        prefs.lastQueryUrl = ChelaileClient.lastUrl
        prefs.lastQueryBody = ChelaileClient.lastBody.take(4000)

        // 多条订阅时轮播，保证每条都有机会上岛
        val withBus = results.filter { it.second.ok && it.second.buses.isNotEmpty() }
        val firstWithBus = if (withBus.isNotEmpty()) withBus[rotateIdx++ % withBus.size] else null
        val anyOk = results.firstOrNull { it.second.ok }

        val lineText: String
        val etaText: String
        var content: String
        when {
            firstWithBus != null -> {
                val (sub, r) = firstWithBus
                lineText = sub.title
                val raw = r.buses[0].etaText
                val hasEta = raw.isNotBlank() && raw != "—"
                etaText = if (hasEta) raw else ""
                val second = r.buses.getOrNull(1)?.etaText
                content = buildString {
                    append("往 ").append(sub.stationName)
                    if (hasEta) {
                        if (!second.isNullOrBlank() && second != raw && second != "—") {
                            append(" · 下一班 ").append(second)
                        }
                        if (r.buses[0].nextStation.isNotBlank()) {
                            append(" · 最近车在 ").append(r.buses[0].nextStation)
                        }
                    } else if (r.lineDesc.isNotBlank()) {
                        append(" · ").append(r.lineDesc)
                    }
                }
            }
            anyOk != null -> {
                val (sub, r) = anyOk
                lineText = sub.title
                etaText = ""
                val extra = r.firstLastText()
                content = listOf(
                    sub.routeText,
                    r.lineDesc.ifBlank { extra.ifBlank { "暂无车辆" } },
                    sub.stationName
                ).filter { it.isNotBlank() }.joinToString(" · ")
            }
            prefs.lastOkAt > 0 -> {
                // 查询失败但有历史结果：显示缓存，避免岛直接消失
                val ageMin = ((System.currentTimeMillis() - prefs.lastOkAt) / 60_000L).toInt().coerceAtLeast(1)
                lineText = prefs.lastOkTitle
                etaText = prefs.lastOkEta
                content = "${ageMin} 分钟前 · " + prefs.lastOkContent
            }
            else -> {
                val sub = results.firstOrNull()?.first
                lineText = sub?.title ?: "公交"
                etaText = ""
                content = results.firstOrNull()?.second?.message ?: "查询失败"
            }
        }

        // 多条订阅时标注总数
        if (withBus.size > 1 && etaText.isNotBlank()) {
            content = "$content · 共 ${withBus.size} 条"
        }

        // 到站提醒
        if (anyOk != null && firstWithBus != null) {
            maybeRemind(firstWithBus.first, firstWithBus.second, etaText, content)
        }

        // 成功一次就记下来，供下次失败兜底
        if (anyOk != null) {
            prefs.lastOkAt = System.currentTimeMillis()
            prefs.lastOkTitle = lineText
            prefs.lastOkEta = etaText
            prefs.lastOkContent = content
        }

        setStatus("$placeName · $lineText $etaText · $content")

        val key = "$lineText|$etaText|$content"
        if (key != lastIslandKey) {
            lastIslandKey = key
            val withIsland = prefs.islandEnabled && HyperIsland.isSupported() &&
                HyperIsland.hasFocusPermission(this)
            if (withIsland) {
                val alert = prefs.islandSound && !islandShown
                islandShown = true
                HyperIsland.showMedia(this, lineText, etaText, content)
                if (alert) Sfx.playNotification(this, Notify.currentSound(this, true))
            } else {
                HyperIsland.showPlain(this, lineText, etaText, content)
            }
        }
    }

    /** 剩余分钟数：从「N 分钟」里取数字 */
    private fun etaMinutes(etaText: String): Int? =
        Regex("""(\d+)\s*分钟""").find(etaText)?.groupValues?.get(1)?.toIntOrNull()

    private fun maybeRemind(sub: LineSub, r: RealTimeResult, etaText: String, content: String) {
        if (!prefs.remindEnabled) return
        val mins = etaMinutes(etaText) ?: return
        if (mins > prefs.remindMinutes) return
        val key = sub.lineId + "|" + mins
        val now = System.currentTimeMillis()
        if (key == prefs.lastRemindKey && now - prefs.lastRemindAt < 5 * 60_000L) return
        prefs.lastRemindKey = key
        prefs.lastRemindAt = now
        notifyArrival(sub, mins, content)
    }

    private fun notifyArrival(sub: LineSub, mins: Int, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = Notify.ensureArrival(this, prefs.remindSound)
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(if (mins <= 0) "${sub.title} 即将到站" else "${sub.title} $mins 分钟后到站")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOnlyAlertOnce(false)
            .build()
        // 同 id 反复 post 会被当成“更新”而不响，先撤掉再发，保证每次都出声
        runCatching { nm.notify(2001, n) }
        // 系统里把渠道铃声设成“无铃声”时，兜底自己响一声，别一点声音都没有
        if (prefs.remindSound && !Notify.channelHasSound(this, true)) {
            Sfx.playNotification(this, Notify.currentSound(this, true))
        }
    }

    private fun cancelIsland() {
        lastIslandKey = ""
        HyperIsland.cancel(this)
        islandShown = false
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        runCatching { lm?.removeUpdates(this) }
        HyperIsland.cancel(this)
        islandShown = false
        stopForeground(true)
        stopSelf()
    }

    // ------------------------------------------------------------ 通知

    private fun createFgChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(FG_CHANNEL) == null) {
            val ch = NotificationChannel(FG_CHANNEL, "后台监控", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun fgNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, FG_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("下一班")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun setStatus(s: String) {
        prefs.lastStatus = s
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(FG_ID, fgNotification(s)) }
    }

    private fun distance(loc: Location, place: Place): Float {
        val r = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, place.lat, place.lng, r)
        return r[0]
    }
}
