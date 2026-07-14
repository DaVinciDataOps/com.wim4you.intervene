package com.wim4you.intervene.ui.video

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/**
 * A [VideoView] that sizes itself and its video surface using corrected display
 * dimensions so portrait recordings are not flattened.
 */
class AspectRatioVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VideoView(context, attrs, defStyleAttr) {

    private var displayWidth = 0
    private var displayHeight = 0

    fun setDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (displayWidth == width && displayHeight == height) return
        displayWidth = width
        displayHeight = height
        applySurfaceSize()
        requestLayout()
    }

    fun setDisplaySizeAfterPrepare(width: Int, height: Int) {
        setDisplaySize(width, height)
        post { setDisplaySize(width, height) }
    }

    private fun applySurfaceSize() {
        if (displayWidth > 0 && displayHeight > 0) {
            holder.setFixedSize(displayWidth, displayHeight)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val containerHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (containerWidth <= 0 || containerHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val aspectRatio = displayWidth.toFloat() / displayHeight.toFloat()
        val height = containerHeight
        val width = (height * aspectRatio).toInt()
        setMeasuredDimension(width, height)
    }
}
