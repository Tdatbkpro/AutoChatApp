package com.example.autochat.ui.phone.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.toColorInt

class MicWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_SOFTWARE, null) // ← force software render, màu hiện rõ hơn
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }

    // 3 vòng sóng với phase lệch nhau
    private val waves = listOf(
        WaveState(color = Color.parseColor("#FF2D55"), delay = 0L),    // iOS red — sáng rực
        WaveState(color = Color.parseColor("#FF6B9D"), delay = 250L),  // pink sáng
        WaveState(color = Color.parseColor("#FFB3CC"), delay = 500L),  // pink nhạt
    )

    private val animators = mutableListOf<ValueAnimator>()
    private var isRunning = false

    data class WaveState(
        val color: Int,
        val delay: Long,
        var radius: Float = 0f,
        var alpha: Float = 0f
    )

    fun startWave() {
        if (isRunning) return
        isRunning = true
        visibility = VISIBLE
        animators.clear()

        waves.forEach { wave ->
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200L
                startDelay = wave.delay
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val progress = it.animatedFraction
                    wave.radius = progress
                    // Alpha: xuất hiện rõ rồi mờ dần
                    wave.alpha = when {
                        progress < 0.2f -> progress / 0.2f
                        else -> 1f - ((progress - 0.2f) / 0.8f)
                    }
                    invalidate()
                }
                start()
            }
            animators.add(animator)
        }
    }

    fun stopWave() {
        isRunning = false
        animators.forEach { it.cancel() }
        animators.clear()
        waves.forEach { it.radius = 0f; it.alpha = 0f }
        visibility = INVISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = width / 2f

        waves.forEach { wave ->
            if (wave.alpha > 0f) {
                wavePaint.color = wave.color
                wavePaint.alpha = (wave.alpha * 255).toInt()
                val r = wave.radius * maxRadius
                canvas.drawCircle(cx, cy, r, wavePaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopWave()
    }
}