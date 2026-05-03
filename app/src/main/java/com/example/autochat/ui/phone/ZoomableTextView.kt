package com.example.autochat.ui.phone

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatTextView

class ZoomableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 3f

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minScale, maxScale)
                scaleX = scaleFactor
                scaleY = scaleFactor
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            scaleGestureDetector.onTouchEvent(it)
        }
        return true
    }

    fun resetZoom() {
        scaleFactor = 1f
        scaleX = 1f
        scaleY = 1f
    }
}