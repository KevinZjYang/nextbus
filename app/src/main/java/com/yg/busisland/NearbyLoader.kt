package com.yg.busisland

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台「附近」加载器：一次定位坐标 → 附近站点 → 并发拉每站线路。
 * 所有网络调用都是阻塞的，这里统一放到后台线程；回调切主线程。
 */
class NearbyLoader {

    private val main = Handler(Looper.getMainLooper())
    private val orchestration = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(4)

    @Volatile
    private var closed = false
    private var generation = 0

    fun load(
        cityId: String,
        cityName: String,
        autoCity: Boolean,
        lat: Double,
        lng: Double,
        label: String,
        udid: String,
        maxStations: Int = 8,
        maxLines: Int = 6,
        timeoutMs: Long = 12_000,
        onDone: (Result<NearbyData>) -> Unit
    ) {
        if (closed) return
        val gen = ++generation
        val stationLimit = maxStations.coerceIn(1, 20)
        val lineLimit = maxLines.coerceIn(1, 20)

        orchestration.execute {
            if (closed || gen != generation) return@execute
            val result = runCatching {
                fetch(
                    cityId = cityId,
                    cityName = cityName,
                    autoCity = autoCity,
                    lat = lat,
                    lng = lng,
                    label = label,
                    udid = udid,
                    maxStations = stationLimit,
                    maxLines = lineLimit,
                    timeoutMs = timeoutMs
                )
            }
            if (closed || gen != generation) return@execute
            main.post {
                if (!closed && gen == generation) onDone(result)
            }
        }
    }

    private fun fetch(
        cityId: String,
        cityName: String,
        autoCity: Boolean,
        lat: Double,
        lng: Double,
        label: String,
        udid: String,
        maxStations: Int,
        maxLines: Int,
        timeoutMs: Long
    ): NearbyData {
        var cid = cityId
        var name = cityName
        var detected: CityInfo? = null

        // 有当前位置时以定位城市为准；否则用设置里的城市，空时再识别一次。
        if (autoCity || cid.isBlank()) {
            detected = runCatching { BusApiClient.localCity(lat, lng, udid) }.getOrNull()
            if (detected != null) {
                cid = detected.id
                name = detected.name
            }
        }
        if (cid.isBlank()) {
            throw IllegalStateException("城市未确定，请到设置里选择城市")
        }

        var stations = runCatching {
            BusApiClient.nearStations(cid, lat, lng, udid)
        }.getOrThrow().take(maxStations)

        // 设置里的城市可能已经过时（出差/搬家），空结果时按坐标再识别一次。
        if (stations.isEmpty() && !autoCity && cityId.isNotBlank()) {
            val again = runCatching { BusApiClient.localCity(lat, lng, udid) }.getOrNull()
            if (again != null && again.id != cid) {
                cid = again.id
                name = again.name
                detected = again
                stations = runCatching {
                    BusApiClient.nearStations(cid, lat, lng, udid)
                }.getOrThrow().take(maxStations)
            }
        }

        val ordered = stations.sortedBy {
            if (it.distance >= 0) it.distance else Int.MAX_VALUE
        }
        if (ordered.isEmpty()) {
            return NearbyData(
                cityId = cid,
                cityName = name,
                detectedCity = detected,
                label = label,
                lat = lat,
                lng = lng,
                stations = emptyList(),
                linesByStation = emptyMap(),
                partial = false,
                fetchedAt = System.currentTimeMillis()
            )
        }

        val linesByStation = ConcurrentHashMap<String, List<StationLine>>()
        val latch = CountDownLatch(ordered.size)
        val failed = AtomicBoolean(false)

        for (station in ordered) {
            runCatching {
                workers.execute {
                    try {
                        val lines = BusApiClient.stationLines(
                            cid, station.physicalStId, lat, lng, udid
                        ).take(maxLines)
                        linesByStation[station.sId] = lines
                    } catch (t: Throwable) {
                        failed.set(true)
                        linesByStation.putIfAbsent(station.sId, emptyList())
                    } finally {
                        latch.countDown()
                    }
                }
            }.onFailure {
                failed.set(true)
                linesByStation.putIfAbsent(station.sId, emptyList())
                latch.countDown()
            }
        }

        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) failed.set(true)

        return NearbyData(
            cityId = cid,
            cityName = name,
            detectedCity = detected,
            label = label,
            lat = lat,
            lng = lng,
            stations = ordered,
            linesByStation = linesByStation.toMap(),
            partial = failed.get(),
            fetchedAt = System.currentTimeMillis()
        )
    }

    fun close() {
        closed = true
        generation++
        main.removeCallbacksAndMessages(null)
        orchestration.shutdownNow()
        workers.shutdownNow()
    }
}
