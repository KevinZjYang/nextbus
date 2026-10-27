package com.yg.busisland.ui

import android.content.Context
import android.graphics.Color

/**
 * 设计语言常量：对齐澎湃 OS 的「大标题 + 分组卡片 + 54dp 行」。
 * 尺寸单位统一为 dp / sp，避免各页面各写一套。
 */
object Ds {

    // 尺寸（dp）
    const val RADIUS_CARD = 20
    const val RADIUS_BUTTON = 25
    const val MARGIN_PAGE = 16
    const val PAD_CARD_H = 16
    const val PAD_CARD_V = 14
    const val ROW_HEIGHT = 54
    const val SECTION_TOP = 22
    const val CARD_GAP = 10
    const val BUTTON_HEIGHT = 50

    // 字阶（sp）
    const val TXT_OVERVIEW_TITLE = 28f
    const val TXT_PAGE_TITLE = 20f
    const val TXT_SUB = 12.5f
    const val TXT_SECTION = 13f
    const val TXT_ROW = 15.5f
    const val TXT_ROW_VALUE = 13f
    const val TXT_BIG = 34f

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}

fun Context.dp(v: Int): Int = Ds.dp(this, v)

/** 取主题色，失败时用 fallback */
fun Context.colorAttr(attr: Int, fallback: Int = Color.BLACK): Int =
    com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

fun Context.colorSurface(): Int =
    colorAttr(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())

fun Context.colorSurfaceVariant(): Int =
    colorAttr(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF6F6F8.toInt())

fun Context.colorOnSurface(): Int =
    colorAttr(com.google.android.material.R.attr.colorOnSurface, 0xFF111114.toInt())

fun Context.colorOnSurfaceVariant(): Int =
    colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF6B6B70.toInt())

fun Context.colorPrimary(): Int =
    colorAttr(com.google.android.material.R.attr.colorPrimary, 0xFF0066FF.toInt())
