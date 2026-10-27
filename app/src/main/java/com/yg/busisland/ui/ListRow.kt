package com.yg.busisland.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 54dp 高的一行：左图标 + 标题 + 右侧值/开关/箭头。
 * 返回右侧的 TextView，方便调用方更新值。
 */
object ListRow {

    fun add(
        parent: LinearLayout,
        icon: String,
        title: String,
        value: String? = null,
        chevron: Boolean = true,
        onClick: (() -> Unit)? = null
    ): TextView {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(Ds.PAD_CARD_H + 2), 0, ctx.dp(Ds.PAD_CARD_H), 0)
            minimumHeight = ctx.dp(Ds.ROW_HEIGHT)
            if (onClick != null) {
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onClick() }
            }
        }

        row.addView(TextView(ctx).apply {
            text = icon
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ctx.dp(30), -2)
        })

        row.addView(TextView(ctx).apply {
            text = title
            textSize = Ds.TXT_ROW
            setTextColor(ctx.colorOnSurface())
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        val valueView = TextView(ctx).apply {
            text = value.orEmpty()
            textSize = Ds.TXT_ROW_VALUE
            setTextColor(ctx.colorOnSurfaceVariant())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.52f).toInt()
            gravity = Gravity.END
        }
        row.addView(valueView)

        if (chevron) {
            row.addView(TextView(ctx).apply {
                text = "›"
                textSize = 18f
                setTextColor(ctx.colorOnSurfaceVariant().let { (it and 0x00FFFFFF) or 0x80000000.toInt() })
                setPadding(ctx.dp(6), 0, 0, 0)
            })
        }

        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        addDivider(parent, row)
        return valueView
    }

    /** 开关行 */
    fun switch(
        parent: LinearLayout,
        icon: String,
        title: String,
        summary: String? = null,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): com.google.android.material.switchmaterial.SwitchMaterial {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(Ds.PAD_CARD_H + 2), ctx.dp(8), ctx.dp(Ds.PAD_CARD_H), ctx.dp(8))
            minimumHeight = ctx.dp(Ds.ROW_HEIGHT)
        }
        row.addView(TextView(ctx).apply {
            text = icon
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ctx.dp(30), -2)
        })
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        }
        titles.addView(TextView(ctx).apply {
            text = title
            textSize = Ds.TXT_ROW
            setTextColor(ctx.colorOnSurface())
            setTypeface(typeface, Typeface.NORMAL)
        })
        if (!summary.isNullOrBlank()) {
            titles.addView(TextView(ctx).apply {
                text = summary
                textSize = Ds.TXT_SUB
                setTextColor(ctx.colorOnSurfaceVariant())
                setPadding(0, ctx.dp(2), 0, 0)
            })
        }
        row.addView(titles)
        val sw = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return sw
    }

    /** 卡片底部说明 */
    fun footnote(parent: LinearLayout, text: String) {
        val ctx = parent.context
        parent.addView(TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.colorOnSurfaceVariant())
            setPadding(ctx.dp(Ds.PAD_CARD_H + 2), ctx.dp(2), ctx.dp(Ds.PAD_CARD_H), ctx.dp(12))
        })
    }

    /** 行之间 1px 分隔线，左缩进对齐标题 */
    private fun addDivider(parent: LinearLayout, after: View) {
        val ctx = parent.context
        val divider = View(ctx).apply {
            setBackgroundColor(0x0F000000)
        }
        parent.addView(divider, LinearLayout.LayoutParams(-1, 1).apply {
            leftMargin = ctx.dp(Ds.PAD_CARD_H + 40)
            rightMargin = ctx.dp(Ds.PAD_CARD_H)
        })
        after.tag = divider
    }
}
