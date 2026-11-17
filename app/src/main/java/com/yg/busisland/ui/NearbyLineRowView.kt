package com.yg.busisland.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.yg.busisland.StationLine

/** 附近线路行：首页和「附近公交」页共用。 */
object NearbyLineRowView {

    fun add(
        parent: LinearLayout,
        line: StationLine,
        stationName: String?,
        distance: Int?,
        subscribed: Boolean,
        onClick: () -> Unit
    ) {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(64)
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            val out = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
            isClickable = true
            setOnClickListener { onClick() }
        }

        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        left.addView(TextView(ctx).apply {
            text = buildString {
                if (subscribed) append("★ ")
                append(line.title)
            }
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.colorOnSurface())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val meta = buildString {
            if (!stationName.isNullOrBlank()) append(stationName)
            if (distance != null && distance >= 0) {
                if (isNotEmpty()) append(" · ")
                append(distance).append("m")
            }
            val extra = line.routeText.ifBlank { line.timeRange }
            if (extra.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(extra)
            }
        }
        if (meta.isNotBlank()) {
            left.addView(TextView(ctx).apply {
                text = meta
                textSize = 12f
                setTextColor(ctx.colorOnSurfaceVariant())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ctx.dp(2), 0, 0)
            })
        }
        row.addView(left)

        row.addView(TextView(ctx).apply {
            text = line.etaText.ifBlank { "—" }
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                if (line.etaText.isNotBlank()) 0xFF1F7A3D.toInt()
                else ctx.colorOnSurfaceVariant()
            )
            setPadding(ctx.dp(10), 0, 0, 0)
        })
        row.addView(TextView(ctx).apply {
            text = "›"
            textSize = 20f
            setTextColor(ctx.colorOnSurfaceVariant())
            setPadding(ctx.dp(6), 0, 0, 0)
        })
        parent.addView(row)
        parent.addView(View(ctx).apply { setBackgroundColor(0x0F000000) },
            LinearLayout.LayoutParams(-1, ctx.dp(1)).apply {
                leftMargin = ctx.dp(16)
                rightMargin = ctx.dp(16)
            })
    }
}
