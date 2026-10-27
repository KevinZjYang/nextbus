package com.yg.busisland.ui

import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.yg.busisland.Ui

/**
 * 工作日多选：7 个可点 chip 内联展示，点一下即写回，没有弹窗。
 * bit0 = 周一 … bit6 = 周日
 */
object WeekdayPicker {

    val NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    /** chip 上只显示一个字，7 个才放得下 */
    private val SHORT = arrayOf("一", "二", "三", "四", "五", "六", "日")

    fun attach(parent: LinearLayout, mask: Int, onChange: (Int) -> Unit) {
        val ctx = parent.context
        var current = mask

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(Ds.PAD_CARD_H + 2), ctx.dp(4), ctx.dp(Ds.PAD_CARD_H), ctx.dp(12))
        }
        val chips = ArrayList<TextView>()

        fun paint() {
            chips.forEachIndexed { i, chip ->
                val on = current and (1 shl i) != 0
                chip.background = Ui.pill(
                    if (on) ctx.colorPrimary() else 0x14000000,
                    ctx.dp(16)
                )
                chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else ctx.colorOnSurface())
            }
        }

        for (i in NAMES.indices) {
            val chip = TextView(ctx).apply {
                text = SHORT[i]
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.NORMAL)
                setPadding(0, ctx.dp(7), 0, ctx.dp(7))
                setOnClickListener {
                    current = current xor (1 shl i)
                    paint()
                    onChange(current)
                }
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) marginStart = ctx.dp(4)
            })
        }
        paint()
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    fun summary(mask: Int): String {
        if (mask == 0) return "未选择"
        if (mask == 0b0011111) return "周一至周五"
        if (mask == 0b1111111) return "每天"
        if (mask == 0b1100000) return "周末"
        return NAMES.indices.filter { mask and (1 shl it) != 0 }.joinToString("、") { NAMES[it] }
    }
}
