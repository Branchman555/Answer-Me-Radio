package radio.reply.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.*

/** Local vector styling: no remote artwork, fonts, permissions or rendering dependencies. */
object RadioTheme {
    val background = Color.rgb(20, 24, 24)
    val surface = Color.rgb(31, 37, 36)
    val border = Color.rgb(65, 73, 68)
    val cream = Color.rgb(246, 236, 214)
    val muted = Color.rgb(179, 187, 175)
    val amber = Color.rgb(246, 184, 99)
    val ink = Color.rgb(42, 31, 20)
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int = surface, radius: Int = 20, stroke: Int = border) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(radius).toFloat(); setStroke(context.dp(1), stroke)
    }
    fun text(context: Context, value: String, size: Float = 16f, color: Int = cream, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = false
    }
    fun column(context: Context, padding: Int = 20) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(context.dp(padding), context.dp(padding), context.dp(padding), context.dp(padding))
    }
    fun button(context: Context, value: String, primary: Boolean = false) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = context.dp(56); minimumHeight = context.dp(56); minimumWidth = 0; minWidth = 0
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) ink else cream)))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
            shape(context, if (primary) amber else surface, 16, if (primary) amber else border), null)
    }
    fun gap(context: Context, height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(height)) }
    @Suppress("DEPRECATION") // API28-34 system bars; API35+ applies its own edge-to-edge policy.
    fun configure(activity: Activity) {
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        activity.window.decorView.systemUiVisibility = 0
    }
}

/** Decorative era dial, never a fake frequency, seek control or measured audio visualizer. */
class RadioDial(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var caseGradient: LinearGradient? = null
    private var faceGradient: LinearGradient? = null
    var era: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        caseGradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xff594736.toInt(), 0xff282d29.toInt(), 0xff453a2d.toInt()), null, Shader.TileMode.CLAMP)
        faceGradient = LinearGradient(w * .12f, h * .16f, w * .88f, h * .73f, intArrayOf(0xfff6ca81.toInt(), 0xffe8a852.toInt(), 0xffbd7b39.toInt()), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val scale = resources.displayMetrics.density
        val edge = 12 * scale
        paint.shader = caseGradient
        canvas.drawRoundRect(0f, 0f, w, h, 24 * scale, 24 * scale, paint)
        paint.shader = null; paint.color = 0xff80705a.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = scale
        canvas.drawRoundRect(edge / 2, edge / 2, w - edge / 2, h - edge / 2, 20 * scale, 20 * scale, paint)
        paint.style = Paint.Style.FILL
        val x = w * .12f; val right = w - x; val top = h * .16f; val bottom = h * .73f
        paint.color = 0xff171d19.toInt(); canvas.drawRoundRect(x - 4 * scale, top - 4 * scale, right + 4 * scale, bottom + 4 * scale, 14 * scale, 14 * scale, paint)
        paint.shader = faceGradient
        canvas.drawRoundRect(x, top, right, bottom, 10 * scale, 10 * scale, paint); paint.shader = null
        paint.color = RadioTheme.ink; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("serif", Typeface.BOLD); paint.textSize = minOf(30 * scale, w * .085f)
        canvas.drawText("응답하라 FM", w / 2, top + (bottom - top) * .35f, paint)
        val start = x + 16 * scale; val end = right - 16 * scale; val baseline = top + (bottom - top) * .65f
        paint.strokeWidth = scale
        for (i in 0..40) {
            val at = start + (end - start) * i / 40
            canvas.drawLine(at, baseline - (if (i % 10 == 0) 13 else if (i % 5 == 0) 9 else 5) * scale, at, baseline, paint)
        }
        canvas.drawLine(start, baseline, end, baseline, paint)
        paint.typeface = Typeface.create("monospace", Typeface.NORMAL); paint.textSize = 11 * scale
        for (i in 0..3) canvas.drawText("${1980 + i * 10}", start + (end - start) * i / 3, baseline + 18 * scale, paint)
        era?.takeIf { it == 1980 || it == 2010 }?.let {
            val marker = if (it == 1980) start else end
            paint.color = 0xffa84b24.toInt(); paint.strokeWidth = 3 * scale
            canvas.drawLine(marker, baseline - 20 * scale, marker, baseline + 4 * scale, paint)
        }
        paint.color = RadioTheme.cream; paint.textSize = 10 * scale
        canvas.drawText("TWO ERAS  /  ONE RADIO", w / 2, h * .9f, paint)
        // Speaker grille, fixed artwork rather than an artificial activity indicator.
        paint.color = 0xff111612.toInt()
        for (i in 0..7) {
            val y = top + i * (bottom - top) / 7
            canvas.drawRoundRect(edge, y, x - 10 * scale, y + 3 * scale, scale, scale, paint)
            canvas.drawRoundRect(right + 10 * scale, y, w - edge, y + 3 * scale, scale, scale, paint)
        }
    }
}
