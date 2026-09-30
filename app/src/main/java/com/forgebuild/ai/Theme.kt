package com.forgebuild.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View

/**
 * Central access to the "cyber forge" palette and reusable drawable builders.
 * Every colour lives in res/values/colors.xml; this object only loads and caches them.
 * [init] is idempotent and is called from MainActivity.onCreate and from the
 * constructors of the custom views, so colours are always ready before first use.
 */
object Theme {
    var BG = Color.BLACK; private set
    var SURFACE = Color.BLACK; private set
    var SURFACE2 = Color.BLACK; private set
    var STROKE = Color.BLACK; private set
    var PRIMARY = Color.GREEN; private set
    var PRIMARY_GLOW = Color.GREEN; private set
    var ACCENT = Color.GREEN; private set
    var DEEP = Color.GREEN; private set
    var TEXT = Color.WHITE; private set
    var TEXT_MUTED = Color.GRAY; private set
    var WARN = Color.YELLOW; private set
    var ERROR = Color.RED; private set
    var INFO = Color.CYAN; private set
    var LOG_BG = Color.BLACK; private set
    var LOG_RAW = Color.LTGRAY; private set
    var ON_PRIMARY = Color.BLACK; private set

    @Volatile private var loaded = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        val c = ctx.applicationContext
        BG = c.getColor(R.color.fb_background)
        SURFACE = c.getColor(R.color.fb_surface)
        SURFACE2 = c.getColor(R.color.fb_surface2)
        STROKE = c.getColor(R.color.fb_stroke)
        PRIMARY = c.getColor(R.color.fb_primary)
        PRIMARY_GLOW = c.getColor(R.color.fb_primary_glow)
        ACCENT = c.getColor(R.color.fb_accent)
        DEEP = c.getColor(R.color.fb_deep)
        TEXT = c.getColor(R.color.fb_text)
        TEXT_MUTED = c.getColor(R.color.fb_text_muted)
        WARN = c.getColor(R.color.fb_warning)
        ERROR = c.getColor(R.color.fb_error)
        INFO = c.getColor(R.color.fb_info)
        LOG_BG = c.getColor(R.color.fb_log_bg)
        LOG_RAW = c.getColor(R.color.fb_log_raw)
        ON_PRIMARY = c.getColor(R.color.fb_on_primary)
        loaded = true
    }

    fun dp(v: View, value: Int): Int =
        (value * v.resources.displayMetrics.density).toInt()

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /** Card: 16dp radius, 1dp stroke, vertical gradient surface -> surface2. */
    fun card(ctx: Context): GradientDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SURFACE, SURFACE2)
        )
        d.cornerRadius = dp(ctx, 16).toFloat()
        d.setStroke(dp(ctx, 1), STROKE)
        return d
    }

    /** Input field background: 12dp radius, surface2 fill, 1dp stroke. */
    fun editBg(ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(SURFACE2)
        d.cornerRadius = dp(ctx, 12).toFloat()
        d.setStroke(dp(ctx, 1), STROKE)
        return d
    }

    /** Primary button: horizontal deep -> primary gradient with a green ripple. */
    fun primaryButtonBg(ctx: Context): RippleDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(DEEP, PRIMARY)
        )
        d.cornerRadius = dp(ctx, 14).toFloat()
        val ripple = ColorStateList.valueOf(withAlpha(PRIMARY_GLOW, 90))
        return RippleDrawable(ripple, d, null)
    }

    /** Secondary button: surface2 fill, stroke, subtle ripple. */
    fun ghostButtonBg(ctx: Context): RippleDrawable {
        val d = GradientDrawable()
        d.setColor(SURFACE2)
        d.cornerRadius = dp(ctx, 14).toFloat()
        d.setStroke(dp(ctx, 1), STROKE)
        val ripple = ColorStateList.valueOf(withAlpha(PRIMARY, 60))
        return RippleDrawable(ripple, d, null)
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Subtle press scale animation (0.97x) used by every button. */
    fun addPressAnimation(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }
}
