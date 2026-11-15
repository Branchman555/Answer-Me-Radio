package radio.reply.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.*
import radio.reply.R

/** Bundled OFL fonts and vector artwork; no network access or UI dependency. */
object RadioTheme {
    val background = Color.rgb(245, 242, 235)
    val surface = Color.rgb(255, 253, 248)
    val border = Color.rgb(222, 219, 210)
    val cream = Color.rgb(35, 48, 46)
    val muted = Color.rgb(105, 112, 105)
    val amber = Color.rgb(155, 77, 36)
    val ink = Color.rgb(255, 249, 235)
    val dark = Color.rgb(28, 48, 44)
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun font(context: Context, bold: Boolean = false): Typeface = context.resources.getFont(if (bold) R.font.pretendard_semibold else R.font.pretendard_regular)
    fun shape(context: Context, color: Int = surface, radius: Int = 20, stroke: Int = border) = GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(radius).toFloat(); setStroke(context.dp(1), stroke)
    }
    fun text(context: Context, value: String, size: Float = 16f, color: Int = cream, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); typeface = font(context, bold)
        includeFontPadding = false; setLineSpacing(context.dp(2).toFloat(), 1f)
    }
    fun column(context: Context, padding: Int = 20) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(context.dp(padding), context.dp(padding), context.dp(padding), context.dp(padding))
    }
    fun button(context: Context, value: String, primary: Boolean = false) = Button(context).apply {
        text = value; isAllCaps = false; textSize = 15f; typeface = font(context, true); includeFontPadding = false
        minHeight = context.dp(56); minimumHeight = context.dp(56); minimumWidth = 0; minWidth = 0
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) ink else cream)))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 155, 125, 80)),
            shape(context, if (primary) dark else surface, 16, if (primary) dark else border), null)
        stateListAnimator = null
    }
    fun gap(context: Context, height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(height)) }
    @Suppress("DEPRECATION")
    fun configure(activity: Activity) {
        activity.window.statusBarColor = background; activity.window.navigationBarColor = background
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
}

/** Physical-radio inspired era scale. Static artwork, not a tuner or visualizer. */
class RadioDial(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = RadioTheme.font(context)
    private val semibold = RadioTheme.font(context, true)
    private var caseGradient: LinearGradient? = null
    private var faceGradient: LinearGradient? = null
    var era: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        caseGradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xff32443d.toInt(), 0xff172823.toInt()), null, Shader.TileMode.CLAMP)
        faceGradient = LinearGradient(0f, h * .22f, 0f, h * .74f, intArrayOf(0xfff3ddb3.toInt(), 0xffd3ae75.toInt()), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        paint.shader = caseGradient
        canvas.drawRoundRect(0f, 0f, w, h, 18*d, 18*d, paint)
        paint.shader = null; paint.color = 0xff657268.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = d*.5f
        canvas.drawRoundRect(5*d, 5*d, w-5*d, h-5*d, 14*d, 14*d, paint); paint.style = Paint.Style.FILL
        paint.typeface = semibold; paint.textSize = 10*d; paint.textAlign = Paint.Align.LEFT; paint.color = 0xffe9ddc8.toInt()
        canvas.drawText("REPLY RADIO", 20*d, h*.15f, paint)
        paint.typeface = regular; paint.textSize = 9*d; paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("1980 / 2010", w-20*d, h*.15f, paint)
        val left = 18*d; val right = w-left; val top = h*.23f; val bottom = h*.76f
        paint.color = 0xff0e1c17.toInt(); canvas.drawRoundRect(left-3*d, top-3*d, right+3*d, bottom+3*d, 8*d, 8*d, paint)
        paint.shader = faceGradient; canvas.drawRoundRect(left, top, right, bottom, 5*d, 5*d, paint); paint.shader = null
        val start = left+22*d; val end = right-22*d
        paint.color = 0xff3c3528.toInt(); paint.typeface = semibold; paint.textSize = minOf(32*d, w*.09f)
        paint.textAlign = Paint.Align.LEFT; canvas.drawText("1980", start, top+(bottom-top)*.48f, paint)
        paint.textAlign = Paint.Align.RIGHT; canvas.drawText("2010", end, top+(bottom-top)*.48f, paint)
        paint.typeface = regular; paint.textAlign = Paint.Align.CENTER; paint.textSize = 9*d
        canvas.drawText("DECADES", w/2, top+(bottom-top)*.43f, paint)
        val baseline = top+(bottom-top)*.78f
        paint.strokeWidth = d*.7f
        for (i in 0..40) {
            val at = start+(end-start)*i/40
            canvas.drawLine(at, baseline-(if (i%5==0) 11 else 5)*d, at, baseline, paint)
        }
        canvas.drawLine(start, baseline+4*d, end, baseline+4*d, paint)
        era?.takeIf { it == 1980 || it == 2010 }?.let {
            val marker = if (it == 1980) start+3*d else end-3*d
            paint.color = 0xffa34a2c.toInt(); paint.strokeWidth = 3*d
            canvas.drawLine(marker, baseline-15*d, marker, baseline+7*d, paint)
        }
        paint.color = 0xff101f1a.toInt(); paint.strokeWidth = d
        for (i in 0..4) canvas.drawLine(left, h*.86f+i*2*d, w*.38f, h*.86f+i*2*d, paint)
        paint.color = 0xffd2bd96.toInt(); paint.textSize = 9*d; paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("두 시대의 음악", right, h*.91f, paint)
    }
}

/** Standard transport symbols with explicit accessibility names. */
class TransportButton @JvmOverloads constructor(context: Context, private var symbol: String = "play", private val primary: Boolean = false) : Button(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    init {
        text = ""; stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x307f8e80), RadioTheme.shape(context,
            if (primary) RadioTheme.dark else RadioTheme.surface, 48, if (primary) RadioTheme.dark else RadioTheme.border), null)
        setSymbol(symbol)
    }
    fun setSymbol(value: String) {
        symbol = value
        contentDescription = when (value) { "pause" -> "일시정지"; "previous" -> "이전 곡"; "next" -> "다음 곡"; "stop" -> "정지"; else -> "재생" }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density; val x = width/2f; val y = height/2f
        paint.color = if (!isEnabled) RadioTheme.muted else if (primary) RadioTheme.ink else RadioTheme.cream
        if (symbol == "pause") {
            canvas.drawRoundRect(x-8*d,y-10*d,x-3*d,y+10*d,d,d,paint); canvas.drawRoundRect(x+3*d,y-10*d,x+8*d,y+10*d,d,d,paint)
        } else if (symbol == "stop") canvas.drawRoundRect(x-6*d,y-6*d,x+6*d,y+6*d,d,d,paint)
        else {
            val direction = if (symbol == "previous") -1 else 1
            path.rewind(); path.moveTo(x-6*d*direction,y-10*d); path.lineTo(x+9*d*direction,y); path.lineTo(x-6*d*direction,y+10*d); path.close(); canvas.drawPath(path,paint)
            if (symbol != "play") canvas.drawRect(x+9*d*direction-d,y-10*d,x+9*d*direction+d,y+10*d,paint)
        }
    }
}
