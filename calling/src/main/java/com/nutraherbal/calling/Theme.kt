package com.nutraherbal.calling

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Colours of the Nutraherbal CRM (same tokens as the web app) so the phone app looks like the CRM. */
object T {
    var accent = Color.parseColor("#5B4CF0")
    var accent2 = Color.parseColor("#8B5CF6")
    var accentDark = Color.parseColor("#3F33C4")
    var accentSoft = Color.parseColor("#ECEAFE")
    var bg = Color.parseColor("#F4F5FB")
    var surface = Color.WHITE
    var ink = Color.parseColor("#1E2235")
    var muted = Color.parseColor("#6B7088")
    var line = Color.parseColor("#E4E6F0")
    var green = Color.parseColor("#10B981")
    var greenSoft = Color.parseColor("#DDF7EE")
    var amber = Color.parseColor("#F59E0B")
    var amberSoft = Color.parseColor("#FEF1D6")
    var coral = Color.parseColor("#F43F5E")
    var coralSoft = Color.parseColor("#FFE3E8")
    var cyan = Color.parseColor("#0EA5C6")
    var cyanSoft = Color.parseColor("#D9F3F9")

    /** v1.5 — dark mode. Colours are read when a screen is built, so the screen is rebuilt (recreate) after a change. */
    var dark = false
    fun isDark(ctx: Context, mode: String): Boolean = when (mode) {
        "dark" -> true
        "light" -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    fun apply(d: Boolean) {
        dark = d
        if (d) {
            accent = Color.parseColor("#7B6CFF"); accent2 = Color.parseColor("#9D7BFF"); accentDark = Color.parseColor("#1B1744"); accentSoft = Color.parseColor("#2B2766")
            bg = Color.parseColor("#0F1220"); surface = Color.parseColor("#1A1E30"); ink = Color.parseColor("#E8EAF6"); muted = Color.parseColor("#9AA1BD"); line = Color.parseColor("#2C3150")
            green = Color.parseColor("#34D399"); greenSoft = Color.parseColor("#0F3B2E"); amber = Color.parseColor("#FBBF24"); amberSoft = Color.parseColor("#40320F")
            coral = Color.parseColor("#FB7185"); coralSoft = Color.parseColor("#4A1A26"); cyan = Color.parseColor("#22C3E6"); cyanSoft = Color.parseColor("#0F3A46")
        } else {
            accent = Color.parseColor("#5B4CF0"); accent2 = Color.parseColor("#8B5CF6"); accentDark = Color.parseColor("#3F33C4"); accentSoft = Color.parseColor("#ECEAFE")
            bg = Color.parseColor("#F4F5FB"); surface = Color.WHITE; ink = Color.parseColor("#1E2235"); muted = Color.parseColor("#6B7088"); line = Color.parseColor("#E4E6F0")
            green = Color.parseColor("#10B981"); greenSoft = Color.parseColor("#DDF7EE"); amber = Color.parseColor("#F59E0B"); amberSoft = Color.parseColor("#FEF1D6")
            coral = Color.parseColor("#F43F5E"); coralSoft = Color.parseColor("#FFE3E8"); cyan = Color.parseColor("#0EA5C6"); cyanSoft = Color.parseColor("#D9F3F9")
        }
    }

    /** The CRM's gradient pairs g1..g5. */
    val grads = listOf(
        intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#8B5CF6")),
        intArrayOf(Color.parseColor("#EC4899"), Color.parseColor("#F43F5E")),
        intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#F97316")),
        intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#14B8A6")),
        intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1"))
    )

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != 0) g.setStroke(dp(ctx, 1), strokeColor)
        return g
    }

    fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    fun gradient(ctx: Context, g: IntArray, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, g)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        return d
    }

    /** Header: rounded only at the bottom. */
    fun headerBg(ctx: Context): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED")))
        val r = dp(ctx, 26).toFloat()
        d.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        return d
    }

    fun avatarGrad(seed: String): IntArray = grads[(seed.hashCode() and 0x7fffffff) % grads.size]

    fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "#"
        val a = parts[0].substring(0, 1).uppercase()
        val b = if (parts.size > 1) parts[1].substring(0, 1).uppercase() else ""
        return a + b
    }

    fun rupees(v: Double): String {
        if (v >= 10000000) return "₹" + String.format("%.1f", v / 10000000) + " Cr"
        if (v >= 100000) return "₹" + String.format("%.1f", v / 100000) + " L"
        if (v >= 1000) return "₹" + String.format("%.1f", v / 1000) + "k"
        return "₹" + v.toLong()
    }

    fun dur(sec: Int): String {
        if (sec <= 0) return "0m"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        if (h > 0) return h.toString() + "h " + m.toString() + "m"
        if (m > 0) return m.toString() + "m " + (sec % 60).toString() + "s"
        return sec.toString() + "s"
    }

    /** 91XXXXXXXXXX → +91XXXXXXXXXX so the phone dials it correctly. */
    fun dialable(number: String): String {
        val d = number.filter { it.isDigit() }
        if (number.trim().startsWith("+")) return "+" + d
        if (d.length == 12 && d.startsWith("91")) return "+" + d
        return d
    }
}
