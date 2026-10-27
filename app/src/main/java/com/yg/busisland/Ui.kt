package com.yg.busisland

import android.content.Context
import android.graphics.drawable.GradientDrawable

object Ui {

    fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    fun pill(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx.toFloat()
        setColor(color)
    }
}
