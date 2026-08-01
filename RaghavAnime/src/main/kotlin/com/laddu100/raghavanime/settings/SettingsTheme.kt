package com.laddu100.raghavanime.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

internal object SettingsTheme {
    val BG_DARK = Color.parseColor("#0A0A0A")
    val BG_CARD = Color.parseColor("#1A1A1A")
    val ACCENT_RED = Color.parseColor("#FF1744")
    val ACCENT_DARK_RED = Color.parseColor("#D50000")
    val TEXT_PRIMARY = Color.parseColor("#FFFFFF")
    val TEXT_SECONDARY = Color.parseColor("#888888")
    val SWITCH_ON = Color.parseColor("#FF1744")
    val SWITCH_OFF = Color.parseColor("#333333")
    val DIVIDER = Color.parseColor("#222222")
    val WARNING = Color.parseColor("#FFA726")

    fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    fun gradient(top: Int, bottom: Int, radius: Float = 16f) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            .apply { cornerRadius = radius }

    fun Int.dp(ctx: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), ctx.resources.displayMetrics).toInt()
}
