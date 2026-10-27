package com.yg.busisland

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * 自己播提示音：MIUI 对「同一条通知反复更新」会吞掉系统提示音，
 * 而且通知渠道的铃声建好就不能改，所以统一由这里按用户选的铃声播放。
 */
object Sfx {

    private const val MAX_MS = 3000L
    private val ui = Handler(Looper.getMainLooper())
    private var cachedUri: String? = null
    private var cached: Ringtone? = null

    fun uriOf(ctx: Context, uriString: String): Uri =
        if (uriString.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else Uri.parse(uriString)

    fun titleOf(ctx: Context, uriString: String): String {
        if (uriString.isBlank()) return "系统默认"
        val raw = runCatching {
            RingtoneManager.getRingtone(ctx, uriOf(ctx, uriString))?.getTitle(ctx)
        }.getOrNull().orEmpty()
        val clean = raw.replace("_preview", "").replace("默认铃声（", "").replace("）", "").trim()
        return clean.ifBlank { "自定义" }
    }

    fun playNotification(ctx: Context, uriString: String = "") {
        runCatching {
            val uri = uriOf(ctx, uriString)
            val key = uri.toString()
            val r = if (cachedUri == key && cached != null) cached!! else {
                val nr = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
                nr.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                cachedUri = key
                cached = nr
                nr
            }
            if (r.isPlaying) r.stop()
            r.play()
            // 系统里不少“通知铃声”其实是整段音乐，统一 3 秒截断
            ui.removeCallbacksAndMessages(null)
            ui.postDelayed({ runCatching { if (r.isPlaying) r.stop() } }, MAX_MS)
        }
    }
}
