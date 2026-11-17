package com.yg.busisland

/**
 * 最近一次附近查询结果，供地图页复用，避免通过 Intent 传递大对象。
 * 只保存在进程内；进程重建后地图入口不可用，回到首页重新查询即可。
 */
object NearbySnapshot {

    @Volatile
    private var data: NearbyData? = null

    fun put(value: NearbyData) {
        data = value
    }

    fun current(): NearbyData? = data
}
