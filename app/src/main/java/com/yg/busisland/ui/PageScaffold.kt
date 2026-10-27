package com.yg.busisland.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import com.yg.busisland.Ui

/**
 * 页面骨架：顶部大标题（二级页带返回箭头）+ 可滚动内容区。
 *
 * ```
 * val page = PageScaffold(this, "通勤监控", "只在时段内定位")
 * page.section("早高峰")
 * val card = page.card()
 * ListRow.add(card, "⏰", "开始", "07:00") { ... }
 * setContentView(page.root)
 * ```
 */
class PageScaffold(
    private val ctx: Context,
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    largeTitle: Boolean = false,
    onBack: (() -> Unit)? = null
) {

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ctx.colorSurfaceVariant())
        setPadding(0, Ui.statusBarHeight(ctx), 0, 0)
    }

    /** 副标题（没有副标题时为 null） */
    var bodyLabel: TextView? = null
        private set

    /** 滚动区里的内容容器，所有卡片/分组加到这里 */
    val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(Ds.MARGIN_PAGE), ctx.dp(4), ctx.dp(Ds.MARGIN_PAGE), ctx.dp(32))
    }

    init {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(if (showBack) 6 else 20), ctx.dp(if (largeTitle) 22 else 12), ctx.dp(16), ctx.dp(6))
        }
        if (showBack) {
            head.addView(com.google.android.material.button.MaterialButton(
                ctx, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "‹"
                textSize = 26f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                setOnClickListener { onBack?.invoke() }
            })
        }
        val titleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(ctx).apply {
            text = title
            textSize = if (largeTitle) Ds.TXT_OVERVIEW_TITLE else Ds.TXT_PAGE_TITLE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.colorOnSurface())
        })
        if (!subtitle.isNullOrBlank()) {
            val sub = TextView(ctx).apply {
                text = subtitle
                textSize = Ds.TXT_SUB
                setTextColor(ctx.colorOnSurfaceVariant())
                setPadding(0, ctx.dp(3), 0, 0)
            }
            bodyLabel = sub
            titleBox.addView(sub)
        }
        head.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head)

        val scroll = NestedScrollView(ctx)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** 清空内容区，用于整页重绘 */
    fun clearBody() = body.removeAllViews()

    /** 分组标题 */
    fun section(text: String) {
        body.addView(TextView(ctx).apply {
            this.text = text
            textSize = Ds.TXT_SECTION
            setTextColor(ctx.colorOnSurfaceVariant())
            setPadding(ctx.dp(4), ctx.dp(Ds.SECTION_TOP), ctx.dp(4), ctx.dp(8))
        })
    }

    /** 分组卡片，返回其中的纵向容器 */
    fun card(): LinearLayout {
        val cardView = MaterialCardView(ctx).apply {
            radius = ctx.dp(Ds.RADIUS_CARD).toFloat()
            cardElevation = 0f
            strokeWidth = ctx.dp(1)
            strokeColor = 0x0F000000
            setCardBackgroundColor(ctx.colorSurface())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(Ds.CARD_GAP) }
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cardView.addView(inner)
        body.addView(cardView)
        return inner
    }

    /** 卡片外的说明文字 */
    fun hint(text: String) {
        body.addView(TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(ctx.colorOnSurfaceVariant())
            setPadding(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(4))
        })
    }

    fun button(text: String, primary: Boolean = true, onClick: () -> Unit): com.google.android.material.button.MaterialButton {
        val btn = com.google.android.material.button.MaterialButton(
            ctx, null,
            if (primary) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            cornerRadius = ctx.dp(Ds.RADIUS_BUTTON)
            setOnClickListener { onClick() }
        }
        body.addView(btn, LinearLayout.LayoutParams(-1, ctx.dp(Ds.BUTTON_HEIGHT)).apply {
            topMargin = ctx.dp(if (primary) 10 else 8)
        })
        return btn
    }
}
