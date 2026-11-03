package com.example.ncphonetrack

import android.content.Context
import android.util.AttributeSet
import androidx.drawerlayout.widget.DrawerLayout

class SafeDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DrawerLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(exact(widthMeasureSpec), exact(heightMeasureSpec))
    }

    private fun exact(spec: Int): Int {
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) return spec
        val size = MeasureSpec.getSize(spec).coerceAtLeast(1)
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    }
}
