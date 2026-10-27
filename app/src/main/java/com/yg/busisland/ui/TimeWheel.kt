package com.yg.busisland.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

/**
 * 时间滚轮：时/分两列滚动 + 大号预览，替代系统 TimePickerDialog。
 * 选完直接回调分钟数，不需要再回上层菜单。
 */
object TimeWheelDialog {

    fun show(ctx: Context, title: String, initialMinutes: Int, onOk: (Int) -> Unit) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(Ds.RADIUS_CARD).toFloat()
                setColor(ctx.colorSurface())
            }
            setPadding(ctx.dp(20), ctx.dp(18), ctx.dp(20), ctx.dp(12))
        }

        box.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.colorOnSurface())
        })

        val preview = TextView(ctx).apply {
            textSize = Ds.TXT_BIG
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.colorPrimary())
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(10), 0, ctx.dp(6))
            text = fmt(initialMinutes)
        }
        box.addView(preview)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ctx.dp(6))
        }

        val hour = NumberPicker(ctx).apply {
            minValue = 0
            maxValue = 23
            wrapSelectorWheel = true
            value = initialMinutes / 60
            setFormatter { "%02d".format(it) }
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val minute = NumberPicker(ctx).apply {
            minValue = 0
            maxValue = 59
            wrapSelectorWheel = true
            value = initialMinutes % 60
            setFormatter { "%02d".format(it) }
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }

        val listener = NumberPicker.OnValueChangeListener { _, _, _ ->
            preview.text = fmt(hour.value * 60 + minute.value)
        }
        hour.setOnValueChangedListener(listener)
        minute.setOnValueChangedListener(listener)

        row.addView(hour, LinearLayout.LayoutParams(ctx.dp(88), -2))
        row.addView(TextView(ctx).apply {
            text = "时"
            textSize = 13f
            setTextColor(ctx.colorOnSurfaceVariant())
            setPadding(ctx.dp(4), 0, ctx.dp(18), 0)
        })
        row.addView(minute, LinearLayout.LayoutParams(ctx.dp(88), -2))
        row.addView(TextView(ctx).apply {
            text = "分"
            textSize = 13f
            setTextColor(ctx.colorOnSurfaceVariant())
            setPadding(ctx.dp(4), 0, 0, 0)
        })
        box.addView(row)

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, ctx.dp(6), 0, 0)
        }
        actions.addView(com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "取消"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        })
        actions.addView(com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = "确定"
            isAllCaps = false
            setTextColor(ctx.colorPrimary())
            setOnClickListener {
                onOk(hour.value * 60 + minute.value)
                dialog.dismiss()
            }
        })
        box.addView(actions)

        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            setLayout(
                (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun fmt(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
}
