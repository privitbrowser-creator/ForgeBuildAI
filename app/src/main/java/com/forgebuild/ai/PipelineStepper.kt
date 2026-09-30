package com.forgebuild.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Horizontal pipeline stepper drawn entirely on a Canvas (no XML, no dependencies).
 * The ACTIVE step pulses with a soft green glow; DONE steps are filled green with a
 * check mark; a FAILED step is red with a cross. Purely presentational: the state
 * machine that drives it lives in MainActivity.
 */
class PipelineStepper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class StepState { PENDING, ACTIVE, DONE, FAILED }

    private var steps: List<String> = emptyList()
    private var states: MutableList<StepState> = mutableListOf()

    private var pulsePhase = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    init {
        Theme.init(context)
    }

    /** Defines the pipeline labels. All steps start PENDING. */
    fun setSteps(labels: List<String>) {
        steps = labels
        states = MutableList(labels.size) { StepState.PENDING }
        updateDescription()
        invalidate()
    }

    fun setState(index: Int, state: StepState) {
        if (index !in states.indices) return
        if (states[index] == state) return
        states[index] = state
        updateDescription()
        syncPulse()
        invalidate()
    }

    fun stateOf(index: Int): StepState =
        if (index in states.indices) states[index] else StepState.PENDING

    fun reset() {
        for (i in states.indices) states[i] = StepState.PENDING
        updateDescription()
        syncPulse()
        invalidate()
    }

    private fun updateDescription() {
        val active = states.indexOf(StepState.ACTIVE)
        contentDescription = if (active >= 0) {
            "Pipeline: step ${active + 1} of ${steps.size}, ${steps[active]}, in progress"
        } else {
            "Pipeline: ${steps.size} steps, none active"
        }
    }

    private fun syncPulse() {
        val hasActive = states.contains(StepState.ACTIVE)
        if (hasActive && pulseAnimator == null) {
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 900
            a.repeatCount = ValueAnimator.INFINITE
            a.repeatMode = ValueAnimator.REVERSE
            a.interpolator = LinearInterpolator()
            a.addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            a.start()
            pulseAnimator = a
        } else if (!hasActive && pulseAnimator != null) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulsePhase = 0f
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = steps.size
        if (n == 0) return

        val w = width - paddingLeft - paddingRight
        if (w <= 0) return
        val slot = w.toFloat() / n
        val r = Theme.dp(this, 9).toFloat()
        val cy = paddingTop + Theme.dp(this, 16).toFloat()
        val strokeW = Theme.dp(this, 2).toFloat()

        labelPaint.textSize = 9f * resources.displayMetrics.scaledDensity
        markPaint.textSize = 10f * resources.displayMetrics.scaledDensity

        // connectors first, circles on top
        for (i in 1 until n) {
            val x0 = paddingLeft + slot * (i - 1) + slot / 2f + r + strokeW
            val x1 = paddingLeft + slot * i + slot / 2f - r - strokeW
            linePaint.strokeWidth = strokeW
            linePaint.color = if (states[i - 1] == StepState.DONE) Theme.PRIMARY else Theme.STROKE
            canvas.drawLine(x0, cy, x1, cy, linePaint)
        }

        for (i in 0 until n) {
            val cx = paddingLeft + slot * i + slot / 2f
            val st = states[i]

            if (st == StepState.ACTIVE) {
                // soft pulsing glow halo
                val haloR = r * (1.9f + 0.5f * pulsePhase)
                val alpha = (70 + 60 * pulsePhase).toInt()
                haloPaint.shader = RadialGradient(
                    cx, cy, haloR,
                    Theme.withAlpha(Theme.PRIMARY_GLOW, alpha),
                    Theme.withAlpha(Theme.PRIMARY_GLOW, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, haloR, haloPaint)
            }

            when (st) {
                StepState.PENDING -> {
                    circlePaint.style = Paint.Style.FILL
                    circlePaint.color = Theme.SURFACE2
                    canvas.drawCircle(cx, cy, r, circlePaint)
                    strokePaint.strokeWidth = Theme.dp(this, 1).toFloat()
                    strokePaint.color = Theme.STROKE
                    canvas.drawCircle(cx, cy, r, strokePaint)
                    markPaint.color = Theme.TEXT_MUTED
                    drawCenteredText(canvas, (i + 1).toString(), cx, cy, markPaint)
                }
                StepState.ACTIVE -> {
                    circlePaint.style = Paint.Style.FILL
                    circlePaint.color = Theme.PRIMARY_GLOW
                    canvas.drawCircle(cx, cy, r, circlePaint)
                    markPaint.color = Theme.ON_PRIMARY
                    drawCenteredText(canvas, (i + 1).toString(), cx, cy, markPaint)
                }
                StepState.DONE -> {
                    circlePaint.style = Paint.Style.FILL
                    circlePaint.color = Theme.PRIMARY
                    canvas.drawCircle(cx, cy, r, circlePaint)
                    markPaint.color = Theme.ON_PRIMARY
                    drawCenteredText(canvas, "✓", cx, cy, markPaint)
                }
                StepState.FAILED -> {
                    circlePaint.style = Paint.Style.FILL
                    circlePaint.color = Theme.ERROR
                    canvas.drawCircle(cx, cy, r, circlePaint)
                    markPaint.color = Theme.TEXT
                    drawCenteredText(canvas, "✗", cx, cy, markPaint)
                }
            }

            // label (split into up to two short lines so 7 steps fit on narrow screens)
            labelPaint.color = when (st) {
                StepState.FAILED -> Theme.ERROR
                StepState.ACTIVE, StepState.DONE -> Theme.TEXT
                StepState.PENDING -> Theme.TEXT_MUTED
            }
            labelPaint.isFakeBoldText = st == StepState.ACTIVE
            val parts = steps[i].split(" ")
            val firstBase = cy + r + Theme.dp(this, 14).toFloat()
            val lineH = labelPaint.textSize * 1.15f
            if (parts.size >= 2) {
                val half = (parts.size + 1) / 2
                canvas.drawText(parts.subList(0, half).joinToString(" "), cx, firstBase, labelPaint)
                canvas.drawText(parts.subList(half, parts.size).joinToString(" "), cx, firstBase + lineH, labelPaint)
            } else {
                canvas.drawText(steps[i], cx, firstBase, labelPaint)
            }
        }
    }

    private fun drawCenteredText(canvas: Canvas, s: String, cx: Float, cy: Float, p: Paint) {
        val baseline = cy - (p.descent() + p.ascent()) / 2f
        canvas.drawText(s, cx, baseline, p)
    }
}
