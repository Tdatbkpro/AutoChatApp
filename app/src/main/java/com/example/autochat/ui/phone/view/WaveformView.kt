package com.example.autochat.ui.phone.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.withStyledAttributes
import com.example.autochat.R
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var playedColor = Color.parseColor("#AB47BC")
    private var unplayedColor = Color.parseColor("#33AAAACC")
    private var heightRatio = 0.75f
    private var barCount = 70
    private var barWidthRatio = 0.55f
    private var glowEnabled = true
    private var cornerRadius = 1.8f

    private val paintPlayed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 230
    }
    private val paintUnplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    private var bars = floatArrayOf()
    private var progress = 0f
    private var currentAnimator: ValueAnimator? = null

    var onSeek: ((Float) -> Unit)? = null
    var isPlaying = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Đọc attributes an toàn
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.WaveformView) {
                playedColor = getColor(
                    R.styleable.WaveformView_wavePlayedColor,
                    playedColor
                )
                unplayedColor = getColor(
                    R.styleable.WaveformView_waveUnplayedColor,
                    unplayedColor
                )
                heightRatio = getFloat(R.styleable.WaveformView_waveHeightRatio, 0.75f)
                barCount = getInt(R.styleable.WaveformView_waveBarCount, 70)
                barWidthRatio = getFloat(R.styleable.WaveformView_waveBarWidthRatio, 0.55f)
                glowEnabled = getBoolean(R.styleable.WaveformView_waveGlowEnabled, true)
                cornerRadius = getDimension(R.styleable.WaveformView_waveCornerRadius, 0f)
            }
        }

        paintPlayed.color = playedColor
        paintUnplayed.color = unplayedColor
        paintGlow.color = Color.argb(40,
            Color.red(playedColor),
            Color.green(playedColor),
            Color.blue(playedColor)
        )
    }

    fun generateBars(count: Int = barCount) {
        bars = FloatArray(count) {
            0.15f + Random.nextFloat() * 0.65f
        }
        setProgress(0f, animate = false)
    }

    fun setProgress(value: Float, animate: Boolean = true) {
        val newProgress = value.coerceIn(0f, 1f)

        if (animate) {
            currentAnimator?.cancel()
            currentAnimator = ValueAnimator.ofFloat(progress, newProgress).apply {
                duration = 150
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = newProgress
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val count = bars.size
        val gap = w / count
        val barW = (gap * barWidthRatio).coerceAtLeast(1.5f)
        val radius = if (cornerRadius > 0) cornerRadius else barW / 2

        for (i in bars.indices) {
            val position = i.toFloat() / count
            val barH = bars[i] * h * heightRatio
            val x = i * gap
            val y = (h - barH) / 2f
            val rect = RectF(x, y, x + barW, y + barH)

            if (position <= progress) {
                if (glowEnabled && isPlaying) {
                    val glowRect = RectF(x - 1, y - 1, x + barW + 1, y + barH + 1)
                    canvas.drawRoundRect(glowRect, radius + 1, radius + 1, paintGlow)
                }
                canvas.drawRoundRect(rect, radius, radius, paintPlayed)
            } else {
                canvas.drawRoundRect(rect, radius, radius, paintUnplayed)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                progress = (event.x / width).coerceIn(0f, 1f)
                onSeek?.invoke(progress)
                invalidate()
                if (event.action == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentAnimator?.cancel()
    }
}