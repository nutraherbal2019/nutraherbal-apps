package com.nutraherbal.calling

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Wraps its children onto new lines (used for the call-tag chips). */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    var gap: Int = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = View.MeasureSpec.getSize(widthSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += w + gap
            if (h > rowH) rowH = h
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(x, y, x + w, y + h)
            x += w + gap
            if (h > rowH) rowH = h
        }
    }
}
