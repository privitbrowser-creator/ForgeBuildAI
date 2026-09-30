package com.forgebuild.ai

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Terminal-style live log panel.
 *
 * - Appends through a [SpannableStringBuilder] (no full re-render while streaming).
 * - Keeps at most [MAX_LINES] lines; oldest are dropped (cheap prefix delete while the
 *   "All" filter is active, one rebuild otherwise).
 * - Auto-scrolls only when the user is already at the bottom; otherwise a floating
 *   "↓ Jump to latest" chip appears.
 * - Toolbar: filter chips (All / Errors / Steps), Copy all, Share as text, Clear.
 * - Long-press a line to copy just that line.
 *
 * All public methods are safe to call from any thread (they hop to the main thread).
 */
class LiveLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Level { INFO, OK, WARN, ERR, STEP, RAW }
    private enum class Filter { ALL, ERRORS, STEPS }

    private data class Entry(val level: Level, val spanned: CharSequence, val plain: String)

    private val entries = ArrayDeque<Entry>()
    private val builder = SpannableStringBuilder()
    private val builderLens = ArrayDeque<Int>() // mirrors the lines currently in `builder`
    private var filter = Filter.ALL
    private var atBottom = true

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val liveLabel: TextView
    private val body: TextView
    private val scroll: ScrollView
    private val jumpChip: TextView
    private val filterChips = mutableListOf<TextView>()
    private var blink: ObjectAnimator? = null

    init {
        Theme.init(context)
        val dp8 = Theme.dp(this, 8)
        val dp10 = Theme.dp(this, 10)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        // ---- header bar: three dots + title + LIVE indicator ----
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(Theme.SURFACE)
        header.setPadding(dp10, 0, dp10, 0)
        header.addView(dot(Theme.ERROR))
        header.addView(dot(Theme.WARN))
        header.addView(dot(Theme.PRIMARY))
        val title = TextView(context)
        title.text = "LIVE LOG"
        title.textSize = 11f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Theme.TEXT_MUTED)
        val titleLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        titleLp.marginStart = dp8
        header.addView(title, titleLp)
        val headerSpacer = View(context)
        header.addView(
            headerSpacer,
            LinearLayout.LayoutParams(0, 1, 1f)
        )
        liveLabel = TextView(context)
        liveLabel.text = "○ IDLE"
        liveLabel.textSize = 11f
        liveLabel.setTextColor(Theme.TEXT_MUTED)
        header.addView(liveLabel)
        column.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 30)
        ))
        column.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 1)
        ))

        // ---- toolbar: filters + actions ----
        val toolbar = LinearLayout(context)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setBackgroundColor(Theme.SURFACE)
        toolbar.setPadding(dp10, 0, dp10, dp8)
        val chipAll = toolbarChip("All") { selectFilter(Filter.ALL) }
        val chipErr = toolbarChip("Errors") { selectFilter(Filter.ERRORS) }
        val chipStep = toolbarChip("Steps") { selectFilter(Filter.STEPS) }
        filterChips.add(chipAll); filterChips.add(chipErr); filterChips.add(chipStep)
        toolbar.addView(chipAll); toolbar.addView(chipErr); toolbar.addView(chipStep)
        val toolbarSpacer = View(context)
        toolbar.addView(toolbarSpacer, LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(toolbarChip("Copy") { copyAll() })
        toolbar.addView(toolbarChip("Share") { shareLog() })
        toolbar.addView(toolbarChip("Clear") { clearLog() })
        column.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 1)
        ))

        // ---- terminal body ----
        scroll = ScrollView(context)
        body = TextView(context)
        body.typeface = Typeface.MONOSPACE
        body.textSize = 12f
        body.setTextColor(Theme.LOG_RAW)
        body.setBackgroundColor(Theme.LOG_BG)
        body.setPadding(dp10, dp8, dp10, dp10)
        installLineCopy()
        scroll.addView(body, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ))
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = scroll.getChildAt(0)
            atBottom = child == null ||
                scrollY + scroll.height >= child.height - Theme.dp(this, 12)
            if (atBottom) hideJumpChip()
        }
        column.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        addView(column, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        // ---- floating "jump to latest" chip ----
        jumpChip = TextView(context)
        jumpChip.text = "↓ Jump to latest"
        jumpChip.textSize = 11f
        jumpChip.typeface = Typeface.DEFAULT_BOLD
        jumpChip.setTextColor(Theme.ON_PRIMARY)
        val chipBg = GradientDrawable()
        chipBg.setColor(Theme.PRIMARY)
        chipBg.cornerRadius = Theme.dp(this, 16).toFloat()
        jumpChip.background = chipBg
        jumpChip.setPadding(
            Theme.dp(this, 12), Theme.dp(this, 7),
            Theme.dp(this, 12), Theme.dp(this, 7)
        )
        jumpChip.elevation = Theme.dp(this, 4).toFloat()
        jumpChip.visibility = View.GONE
        jumpChip.setOnClickListener {
            atBottom = true
            scrollToBottom()
            hideJumpChip()
        }
        val chipLp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        )
        chipLp.setMargins(0, 0, Theme.dp(this, 12), Theme.dp(this, 12))
        addView(jumpChip, chipLp)

        repaintFilterChips()
    }

    // ---------- public API ----------

    /** Appends one decorated, timestamped line. */
    fun log(line: String, level: Level = Level.INFO) = onMain {
        val (spanned, plain) = decorate(line, level)
        appendEntry(Entry(level, spanned, plain))
    }

    /** Appends a raw block (e.g. a GitHub job log) with ##[…] highlighting. */
    fun logRawBlock(text: String) = onMain {
        for (ln in text.split('\n')) {
            val (spanned, plain) = decorateRaw(ln)
            entries.addLast(Entry(Level.RAW, spanned, plain))
            if (entries.size > MAX_LINES) entries.removeFirst()
        }
        renderAll()
    }

    /** Replaces the whole content (used where the old plain TextView was re-set). */
    fun setBody(text: String) = onMain {
        entries.clear()
        val lines = text.split('\n')
        val start = if (lines.size > MAX_LINES) lines.size - MAX_LINES else 0
        for (i in start until lines.size) {
            val (spanned, plain) = decorateRaw(lines[i])
            entries.addLast(Entry(Level.RAW, spanned, plain))
        }
        renderAll()
    }

    /** Appends an error message (multi-line) in error styling. */
    fun error(text: String) = onMain {
        for (ln in text.split('\n')) {
            val (spanned, plain) = decorate(ln, Level.ERR)
            appendEntry(Entry(Level.ERR, spanned, plain))
        }
    }

    fun clearLog() = onMain {
        entries.clear()
        builder.clear()
        builderLens.clear()
        body.text = ""
        atBottom = true
        hideJumpChip()
    }

    /** Starts/stops the blinking "● LIVE" indicator in the header. */
    fun setLive(on: Boolean) = onMain {
        if (on) {
            liveLabel.text = "● LIVE"
            liveLabel.setTextColor(Theme.PRIMARY_GLOW)
            if (blink == null) {
                val b = ObjectAnimator.ofFloat(liveLabel, View.ALPHA, 1f, 0.25f)
                b.duration = 700
                b.repeatCount = ObjectAnimator.INFINITE
                b.repeatMode = ObjectAnimator.REVERSE
                b.start()
                blink = b
            }
        } else {
            blink?.cancel()
            blink = null
            liveLabel.alpha = 1f
            liveLabel.text = "○ IDLE"
            liveLabel.setTextColor(Theme.TEXT_MUTED)
        }
    }

    // ---------- internals ----------

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun passes(level: Level): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.ERRORS -> level == Level.ERR
        Filter.STEPS -> level == Level.STEP
    }

    private fun appendEntry(e: Entry) {
        entries.addLast(e)
        if (entries.size > MAX_LINES) {
            entries.removeFirst()
            if (filter == Filter.ALL) {
                // builder mirrors entries exactly: drop the oldest rendered line too
                if (builderLens.isNotEmpty()) builder.delete(0, builderLens.removeFirst())
            } else {
                renderAll()
                return
            }
        }
        if (passes(e.level)) {
            builder.append(e.spanned).append('\n')
            builderLens.addLast(e.spanned.length + 1)
            body.text = builder
            afterContentChange()
        }
    }

    private fun renderAll() {
        builder.clear()
        builderLens.clear()
        for (e in entries) {
            if (passes(e.level)) {
                builder.append(e.spanned).append('\n')
                builderLens.addLast(e.spanned.length + 1)
            }
        }
        body.text = builder
        afterContentChange()
    }

    private fun afterContentChange() {
        if (atBottom) {
            scrollToBottom()
        } else if (jumpChip.visibility != View.VISIBLE) {
            jumpChip.visibility = View.VISIBLE
            jumpChip.alpha = 0f
            jumpChip.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideJumpChip() {
        if (jumpChip.visibility == View.VISIBLE) jumpChip.visibility = View.GONE
    }

    private fun levelColor(level: Level): Int = when (level) {
        Level.INFO -> Theme.INFO
        Level.OK -> Theme.PRIMARY
        Level.WARN -> Theme.WARN
        Level.ERR -> Theme.ERROR
        Level.STEP -> Theme.PRIMARY_GLOW
        Level.RAW -> Theme.LOG_RAW
    }

    /** Timestamped + tagged line. Returns the spanned text and its plain form. */
    private fun decorate(line: String, level: Level): Pair<CharSequence, String> {
        val ts = timeFmt.format(Date())
        val tag = when (level) {
            Level.INFO -> "[info] "
            Level.OK -> "[ok] "
            Level.WARN -> "[warn] "
            Level.ERR -> "[err] "
            Level.STEP -> "[step] "
            Level.RAW -> ""
        }
        val full = "$ts $tag$line"
        val s = SpannableString(full)
        val from = ts.length + 1
        s.setSpan(
            ForegroundColorSpan(Theme.TEXT_MUTED), 0, from,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (full.length > from) {
            s.setSpan(
                ForegroundColorSpan(levelColor(level)), from, full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (level == Level.STEP) s.setSpan(
                StyleSpan(Typeface.BOLD), from, full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return s to full
    }

    /** Raw GitHub log line with ##[group] / ##[error] / ::error style highlighting. */
    private fun decorateRaw(line: String): Pair<CharSequence, String> {
        val lower = line.lowercase(Locale.US)
        val color = when {
            line.contains("##[error]") || lower.contains("::error") -> Theme.ERROR
            line.contains("##[warning]") || lower.contains("::warning") -> Theme.WARN
            line.contains("##[group]") || line.contains("##[endgroup]") -> Theme.PRIMARY_GLOW
            line.contains("##[command]") -> Theme.INFO
            else -> Theme.LOG_RAW
        }
        val s = SpannableString(line)
        if (line.isNotEmpty()) {
            s.setSpan(
                ForegroundColorSpan(color), 0, line.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (line.contains("##[group]") || line.contains("##[error]")) s.setSpan(
                StyleSpan(Typeface.BOLD), 0, line.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return s to line
    }

    // ---------- toolbar actions ----------

    private fun plainText(): String {
        val sb = StringBuilder()
        for (e in entries) {
            if (passes(e.level)) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(e.plain)
            }
        }
        return sb.toString()
    }

    private fun copyAll() {
        val text = plainText()
        if (text.isBlank()) {
            toast("Log is empty")
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ForgeBuild live log", text))
        toast("Log copied")
    }

    private fun shareLog() {
        val text = plainText()
        if (text.isBlank()) {
            toast("Log is empty")
            return
        }
        val capped = if (text.length > 400000) text.takeLast(400000) else text
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, "ForgeBuild live log")
        i.putExtra(Intent.EXTRA_TEXT, capped)
        try {
            context.startActivity(Intent.createChooser(i, "Share log"))
        } catch (e: Exception) {
            toast("No app can share the log")
        }
    }

    private fun selectFilter(f: Filter) {
        if (filter == f) return
        filter = f
        repaintFilterChips()
        renderAll()
    }

    private fun repaintFilterChips() {
        val activeIndex = when (filter) {
            Filter.ALL -> 0
            Filter.ERRORS -> 1
            Filter.STEPS -> 2
        }
        for (i in filterChips.indices) {
            val c = filterChips[i]
            val bg = GradientDrawable()
            if (i == activeIndex) {
                bg.setColor(Theme.PRIMARY)
                c.setTextColor(Theme.ON_PRIMARY)
                c.typeface = Typeface.DEFAULT_BOLD
            } else {
                bg.setColor(Theme.SURFACE2)
                bg.setStroke(Theme.dp(this, 1), Theme.STROKE)
                c.setTextColor(Theme.TEXT_MUTED)
                c.typeface = Typeface.DEFAULT
            }
            bg.cornerRadius = Theme.dp(this, 12).toFloat()
            c.background = bg
        }
    }

    // ---------- small view builders ----------

    private fun dot(color: Int): View {
        val v = View(context)
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        v.background = d
        val size = Theme.dp(this, 9)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.marginEnd = Theme.dp(this, 6)
        v.layoutParams = lp
        return v
    }

    private fun divider(): View {
        val v = View(context)
        v.setBackgroundColor(Theme.STROKE)
        return v
    }

    private fun toolbarChip(label: String, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = label
        t.textSize = 11f
        t.setTextColor(Theme.TEXT_MUTED)
        val bg = GradientDrawable()
        bg.setColor(Theme.SURFACE2)
        bg.cornerRadius = Theme.dp(this, 12).toFloat()
        bg.setStroke(Theme.dp(this, 1), Theme.STROKE)
        t.background = bg
        t.setPadding(
            Theme.dp(this, 10), Theme.dp(this, 4),
            Theme.dp(this, 10), Theme.dp(this, 4)
        )
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = Theme.dp(this, 6)
        t.layoutParams = lp
        t.setOnClickListener { onClick() }
        return t
    }

    private var lastTouchY = 0f

    /** Long-press copies the touched line (the body itself is not selectable). */
    private fun installLineCopy() {
        body.setOnTouchListener { _, ev ->
            lastTouchY = ev.y
            false
        }
        body.setOnLongClickListener {
            val layout = body.layout ?: return@setOnLongClickListener false
            val y = (lastTouchY.toInt() - body.totalPaddingTop).coerceAtLeast(0)
            val line = layout.getLineForVertical(y)
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            val txt = body.text.substring(start, end).trimEnd()
            if (txt.isNotEmpty()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ForgeBuild log line", txt))
                toast("Line copied")
                true
            } else {
                false
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    }

    override fun onDetachedFromWindow() {
        blink?.cancel()
        blink = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MAX_LINES = 3000
    }
}
