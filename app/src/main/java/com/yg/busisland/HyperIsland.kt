package com.yg.busisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 小米澎湃OS 超级岛（灵动岛）。
 *
 * 官方通道是 Notification.extras["miui.focus.param"]，但 SystemUI 的 FocusPlugin 会校验应用签名
 * 与小米白名单（onAuthFailed → removeByKey），个人开发者拿不到，所以这里走**媒体通知**：
 * 挂一个 MediaSession + MediaStyle，系统会把它渲染成「音乐卡片」上岛，免授权。
 *
 * 代价：外形偏音乐卡；可用信息仍然是线路名 + 到站时间。
 */
object HyperIsland {

    const val CHANNEL_ID = "bus_island"
    const val NOTIFY_ID = 1001

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "公交灵动岛", NotificationManager.IMPORTANCE_DEFAULT)
            ch.setShowBadge(false)
            ch.setSound(null, null) // 声音由 Sfx 播，避免每次刷新都响
            nm.createNotificationChannel(ch)
        }
    }

    /** persist.sys.feature.island */
    fun isSupported(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val getBoolean = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        getBoolean.invoke(null, "persist.sys.feature.island", false) as Boolean
    }.getOrDefault(false)

    /** Settings.System.notification_focus_protocol：1=OS1, 2=OS2, 3=OS3(超级岛) */
    fun protocolVersion(ctx: Context): Int = runCatching {
        Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    /** 焦点通知权限（content://miui.statusbar.notification.public / canShowFocus） */
    fun hasFocusPermission(ctx: Context): Boolean = runCatching {
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = android.os.Bundle().apply { putString("package", ctx.packageName) }
        val b = ctx.contentResolver.call(uri, "canShowFocus", null, extras)
        b?.getBoolean("canShowFocus", false) ?: false
    }.getOrDefault(false)

    fun openFocusSettings(ctx: Context) {
        val intents = listOf(
            Intent("miui.intent.action.FOCUS_NOTIFICATION_SETTINGS").setPackage("com.android.settings"),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
    }

    private var mediaSession: android.media.session.MediaSession? = null

    private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 上岛：媒体卡片（免授权） */
    fun showMedia(ctx: Context, line: String, eta: String, content: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ticker = if (eta.isBlank()) line else "$line · $eta"

        val session = mediaSession ?: android.media.session.MediaSession(ctx, "BusIsland").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {})
            isActive = true
            mediaSession = this
        }
        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, ticker)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, content)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "公交到站")
                .build()
        )
        session.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(0)
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )

        val open = openIntent(ctx)
        val refresh = refreshIntent(ctx)
        session.setSessionActivity(open)

        // 用应用图标作为大图，岛收起态显示的就是它
        val largeIcon: android.graphics.Bitmap? = runCatching {
            val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
            val w = maxOf(1, d.intrinsicWidth)
            val h = maxOf(1, d.intrinsicHeight)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(android.graphics.Canvas(bmp))
            bmp
        }.getOrNull()

        val icon = android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_rotate)
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(ticker)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .addAction(Notification.Action.Builder(icon, "刷新", refresh).build())
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        nm.notify(NOTIFY_ID, builder.build())
    }

    /** 不支持超级岛时的普通常驻通知 */
    fun showPlain(ctx: Context, line: String, eta: String, content: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ticker = if (eta.isBlank()) line else "$line · $eta"
        val icon = android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_rotate)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(ticker)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent(ctx))
            .addAction(Notification.Action.Builder(icon, "刷新", refreshIntent(ctx)).build())
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFY_ID)
        mediaSession?.let { s ->
            runCatching {
                s.setPlaybackState(
                    android.media.session.PlaybackState.Builder()
                        .setState(android.media.session.PlaybackState.STATE_NONE, 0, 0f)
                        .build()
                )
                s.isActive = false
                s.release()
            }
            mediaSession = null
        }
    }
}
